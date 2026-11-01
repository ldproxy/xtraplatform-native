/*
 * Copyright 2026 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.xtralink.app;

import static de.ii.xtraplatform.base.domain.util.JacksonModules.DESERIALIZE_IMMUTABLE_BUILDER_NESTED;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.azahnen.dagger.annotations.AutoBind;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import dagger.Lazy;
import de.ii.xtralink.jobs.BaseJob;
import de.ii.xtralink.jobs.Identifiers.Queue;
import de.ii.xtralink.jobs.Identifiers.Result;
import de.ii.xtralink.jobs.Identifiers.Status;
import de.ii.xtralink.jobs.InitProgress;
import de.ii.xtralink.jobs.Job;
import de.ii.xtralink.jobs.JobConfiguration;
import de.ii.xtralink.jobs.JobResult;
import de.ii.xtralink.jobs.PartialJob;
import de.ii.xtralink.jobs.PartialJobConfiguration;
import de.ii.xtralink.jobs.QueueConfiguration;
import de.ii.xtralink.jobs.SetOutputs;
import de.ii.xtralink.jobs.internal.CancelListener;
import de.ii.xtralink.jobs.internal.JobListener;
import de.ii.xtralink.jobs.internal.JobProcessor.Registration;
import de.ii.xtralink.jobs.internal.JobQueue;
import de.ii.xtraplatform.base.domain.AppContext;
import de.ii.xtraplatform.base.domain.AppLifeCycle;
import de.ii.xtraplatform.base.domain.Jackson;
import de.ii.xtraplatform.base.domain.JobsConfiguration.QUEUE;
import de.ii.xtraplatform.base.domain.LogContext;
import de.ii.xtraplatform.base.domain.LogContext.MARKER;
import de.ii.xtraplatform.xtralink.domain.JobContext;
import de.ii.xtraplatform.xtralink.domain.JobContext.JobContextEntity;
import de.ii.xtraplatform.xtralink.domain.JobInputs;
import de.ii.xtraplatform.xtralink.domain.JobProcessing;
import de.ii.xtraplatform.xtralink.domain.JobProcessorBase;
import de.ii.xtraplatform.xtralink.domain.Jobs;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threeten.extra.AmountFormats;

@Singleton
@AutoBind
public class JobsImpl implements Jobs, JobProcessing, AppLifeCycle {

  private static final Logger LOGGER = LoggerFactory.getLogger(JobsImpl.class);
  // see logWaitingWithoutProcessor
  private static final Duration WAITING_REPORT_DELAY = Duration.ofSeconds(60);

  private final AppContext appContext;
  private final Lazy<Set<JobProcessorBase>> processors;
  private final List<Registration> registrations;
  private final ObjectMapper objectMapper;
  private final Map<String, Status> jobStatus;
  private final Map<String, JobListener.Registration> jobRegistration;
  // the listeners of the jobs pushed here, and the last state passed on to each, see notifyListener
  private final Map<String, JobListener> jobListeners;
  private final Map<String, Integer> notifiedState;
  private final Map<String, ActiveJob> activeJobs;
  // the kinds of the partial jobs the processors of this instance take, see canProcess
  private final Set<String> processedKinds;
  private final ScheduledExecutorService polling;
  private CancelListener.Registration cancelRegistration;
  // only read and written by the single polling thread
  private boolean polled;
  // the jobs reported as waiting without a processor of this instance, only used by the polling
  // thread, see logWaitingWithoutProcessor
  private final Set<String> reportedWaiting = new HashSet<>();

  @Inject
  JobsImpl(AppContext appContext, Jackson jackson, Lazy<Set<JobProcessorBase>> processors) {
    this.appContext = appContext;
    this.processors = processors;
    this.registrations = new CopyOnWriteArrayList<>();
    this.objectMapper =
        jackson.getDefaultObjectMapper().registerModule(DESERIALIZE_IMMUTABLE_BUILDER_NESTED);
    this.jobStatus = new ConcurrentHashMap<>();
    this.jobRegistration = new ConcurrentHashMap<>();
    this.jobListeners = new ConcurrentHashMap<>();
    this.notifiedState = new ConcurrentHashMap<>();
    this.activeJobs = new ConcurrentHashMap<>();
    this.processedKinds = ConcurrentHashMap.newKeySet();
    this.polling =
        MoreExecutors.getExitingScheduledExecutorService(
            (ScheduledThreadPoolExecutor)
                Executors.newScheduledThreadPool(
                    1, new ThreadFactoryBuilder().setNameFormat("jobs.poll-%d").build()));
  }

  @Override
  public CompletionStage<Void> onStart(boolean isStartupAsync) {
    System.setProperty("genffi.threads.xtralink", "jobs");
    new XtralinkLoaderImpl().load();

    boolean persistentQueue = appContext.getConfiguration().getJobs().getQueue() == QUEUE.REDIS;

    Set<JobProcessorBase> procs = processors.get();
    if (Objects.nonNull(procs)) {
      procs.forEach(this::register);
      procs.forEach(
          processor -> {
            try {
              processor.beforeQueueStart(persistentQueue);
            } catch (Throwable e) {
              LogContext.error(
                  LOGGER, e, "Error preparing job processor {}", processor.getClass().getName());
            }
          });
    }

    this.cancelRegistration = CancelListener.register(this::onCancel);
    JobQueue.onCancel(cancelRegistration);

    QueueConfiguration queueConfiguration =
        new QueueConfiguration(
            appContext.getConfiguration().getJobConcurrency(),
            appContext.getInstanceName(),
            persistentQueue ? Queue.REDIS : Queue.LOCAL,
            appContext.getConfiguration().getRedis().getCluster(),
            appContext.getConfiguration().getRedis().getNodes());

    JobQueue.start(queueConfiguration);

    polling.scheduleAtFixedRate(this::pollJobs, 5, 5, TimeUnit.SECONDS);

    return AppLifeCycle.super.onStart(isStartupAsync);
  }

  @Override
  public void onStop() {
    registrations.forEach(Registration::close);

    if (Objects.nonNull(cancelRegistration)) {
      cancelRegistration.close();
    }

    JobQueue.stop();
  }

  @Override
  public Job push(JobConfiguration job, JobListener onChange) {
    // LOGGER.info("JOBS: Pushing job: {}", job.label());

    JobListener onProgress2 =
        (j) -> {
          onJobUpdate(j);
          notifyListener(j, onChange);
        };
    JobListener.Registration registration = JobListener.register(onProgress2);

    Job pushedJob = JobQueue.push(job, registration);

    jobRegistration.put(pushedJob.id(), registration);
    jobListeners.put(pushedJob.id(), onChange);
    onJobUpdate(pushedJob);

    return pushedJob;
  }

  @Override
  public PartialJob push(PartialJobConfiguration partialJob) {
    // LOGGER.info("JOBS: Pushing partial job: {}", partialJob.kind());
    return JobQueue.pushPartial(partialJob);
  }

  @Override
  public PartialJob repush(String id) {
    // LOGGER.info("JOBS: Re-Pushing partial job: {}", id);
    return JobQueue.repushPartial(id);
  }

  @Override
  public CompletableFuture<Job> waitFor(String id) {
    return JobQueue.waitFor(id);
  }

  @Override
  public Optional<Job> get(String id) {
    return JobQueue.get(id);
  }

  @Override
  public List<Job> list() {
    return JobQueue.list().jobs();
  }

  @Override
  public List<PartialJob> taken() {
    return JobQueue.taken().partialJobs();
  }

  @Override
  public List<PartialJob> failed() {
    return JobQueue.failed().partialJobs();
  }

  @Override
  public boolean cancel(String id) {
    return JobQueue.cancel(id);
  }

  @Override
  public boolean delete(String id) {
    return JobQueue.delete(id);
  }

  @Override
  public void init(String jobId, int progressTotal, Map<String, ?> progressDetails) {
    InitProgress initProgress =
        new InitProgress(progressTotal, (Map<String, Object>) progressDetails);

    JobQueue.init(jobId, initProgress);
  }

  @Override
  public void update(String partialJobId, int delta) {
    JobQueue.updatePartial(partialJobId, delta);
  }

  @Override
  public void outputs(String id, Object outputs) {
    try {
      byte[] bytes = objectMapper.writeValueAsBytes(outputs);
      Map<String, Object> outputsMap = objectMapper.readValue(bytes, Jobs.MAP_TYPE);
      JobQueue.outputs(id, new SetOutputs(outputsMap));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public <T extends JobInputs> T getInputs(Job job, Class<T> inputsClass) {
    if (inputsClass == JobInputs.JobInputsNone.class) {
      return (T) JobInputs.NONE;
    }
    try {
      byte[] bytes = objectMapper.writeValueAsBytes(job.inputs());
      return objectMapper.readValue(bytes, inputsClass);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public <T extends JobContext> T getContext(BaseJob job, Class<T> contextClass) {
    if (contextClass == JobContext.JobContextNone.class) {
      return (T) JobContext.NONE;
    }
    try {
      byte[] bytes = objectMapper.writeValueAsBytes(job.context());
      return objectMapper.readValue(bytes, contextClass);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean isCancelled(String jobId) {
    ActiveJob activeJob = activeJobs.get(jobId);
    return Objects.nonNull(activeJob) && activeJob.cancelled;
  }

  // Only jobs with partial jobs running here can be cancelled early, others are ignored, so the
  // map stays bounded by what is in flight.
  private void onCancel(String jobId) {
    activeJobs.computeIfPresent(
        jobId,
        (id, activeJob) -> {
          activeJob.cancelled = true;
          return activeJob;
        });
  }

  // onCancel ignores jobs without an entry, so a cancel just before this partial job started is
  // only visible in the status of the job.
  private void beginPartialJob(Job job) {
    activeJobs.compute(
        job.id(),
        (id, activeJob) -> {
          ActiveJob current = Objects.requireNonNullElseGet(activeJob, ActiveJob::new);
          current.partialJobs++;
          if (job.status() == Status.DISMISSED) {
            current.cancelled = true;
          }
          return current;
        });
  }

  private void endPartialJob(Job job) {
    activeJobs.computeIfPresent(
        job.id(), (id, activeJob) -> --activeJob.partialJobs == 0 ? null : activeJob);
  }

  private static final class ActiveJob {
    private int partialJobs;
    private volatile boolean cancelled;
  }

  private void register(JobProcessorBase processor) {
    de.ii.xtralink.jobs.internal.JobProcessor processorWrapper =
        (partialJob, job) -> {
          Instant start = Instant.now();
          setupExecutionContext(job);
          logJobProcessingStart(partialJob);
          beginPartialJob(job);
          JobResult result;
          try {
            result = processor.process(partialJob, job, this);
          } finally {
            endPartialJob(job);
          }
          logJobProcessingEnd(result, start);

          return result;
        };

    Registration registration =
        de.ii.xtralink.jobs.internal.JobProcessor.register(processorWrapper);
    registrations.add(registration);

    for (String kind : processor.getKinds()) {
      try {
        JobQueue.register(kind, processor.getPriority(), registration);
        processedKinds.add(kind);
        // LOGGER.info("JOBS: Registered job processor for kind: {}", kind);
      } catch (Throwable e) {
        LOGGER.error("JOBS: Failed to register job processor for kind: {}", kind, e);
        registration.close();
      }
    }
  }

  // The listener of a job learns its changes from the queue, which calls it only for the LOCAL
  // queue, and from polling, the only source for the REDIS queue. Each state is passed on once and
  // only if it moves the job forward, whichever source comes first; when the job is finished, its
  // listener is released.
  private synchronized void notifyListener(Job job, JobListener listener) {
    int state = state(job);
    Integer previous = notifiedState.get(job.id());
    if (previous != null && state <= previous) {
      return;
    }
    notifiedState.put(job.id(), state);

    if (!isActive(job)) {
      jobListeners.remove(job.id());
      closeRegistration(job);
    }

    listener.onProgress(job);
  }

  // accepted < running, by progress < finished
  private static int state(Job job) {
    return switch (job.status()) {
      case ACCEPTED -> 0;
      case RUNNING -> 1 + Math.max(0, Math.min(100, percent(job)));
      default -> 102;
    };
  }

  private static int percent(Job job) {
    return Objects.nonNull(job.progress()) && Objects.nonNull(job.progress().percent())
        ? job.progress().percent()
        : 0;
  }

  private void closeRegistration(Job job) {
    JobListener.Registration removed = jobRegistration.remove(job.id());
    if (removed != null) {
      removed.close();
    }
  }

  private void setupExecutionContext(Job job) {
    if (JobContextEntity.is(job.context())) {
      LogContext.put(LogContext.CONTEXT.SERVICE, JobContextEntity.from(job.context()));
    }
  }

  // Updates come from listener callbacks and from polling, possibly out of order, so a status only
  // counts if it moves the job forward. Follow-ups pushed by xtralink itself and all jobs on the
  // REDIS queue have no listener, polling is the only source for them (also for the listeners of
  // the jobs pushed here, see notifyListener).
  private void onJobUpdate(Job job) {
    onJobUpdate(job, false);
  }

  // Active jobs found by the first poll were queued before this start, e.g. on the REDIS queue
  // before a restart, so they are logged as waiting or running since then.
  private synchronized void onJobUpdate(Job job, boolean queuedBefore) {
    Status previous = jobStatus.get(job.id());
    if (previous != null && stage(job.status()) <= stage(previous)) {
      return;
    }
    jobStatus.put(job.id(), job.status());
    logJobStatus(job, queuedBefore && previous == null);
  }

  private static int stage(Status status) {
    return switch (status) {
      case ACCEPTED -> 0;
      case RUNNING -> 1;
      default -> 2;
    };
  }

  private void pollJobs() {
    try {
      List<Job> jobs = list();
      Set<String> listed = new HashSet<>();
      boolean firstPoll = !polled;

      for (Job job : jobs) {
        listed.add(job.id());
        // finished jobs never seen active are history, e.g. from before a restart
        if (isActive(job) || jobStatus.containsKey(job.id())) {
          onJobUpdate(job, firstPoll);
        }
        JobListener listener = jobListeners.get(job.id());
        if (listener != null) {
          notifyListener(job, listener);
        }
      }
      jobStatus.entrySet().removeIf(e -> !isActive(e.getValue()) && !listed.contains(e.getKey()));
      notifiedState.keySet().removeIf(id -> !listed.contains(id) && !jobListeners.containsKey(id));

      logWaitingWithoutProcessor(jobs, listed);
      logActiveJobSetProgress(jobs);
      polled = true;
    } catch (Throwable e) {
      LogContext.error(LOGGER, e, "Error while polling jobs");
    }
  }

  // A job may well wait for another executor on the same queue, e.g. a worker of a workflow, so
  // only a job that is still waiting after a while is reported, once.
  private void logWaitingWithoutProcessor(List<Job> jobs, Set<String> listed) {
    reportedWaiting.retainAll(listed);
    long now = Instant.now().toEpochMilli();
    for (Job job : jobs) {
      if (job.status() == Status.ACCEPTED
          && Objects.nonNull(job.createdAt())
          && now - job.createdAt() >= WAITING_REPORT_DELAY.toMillis()
          && !canProcess(job)
          && reportedWaiting.add(job.id())
          && (LOGGER.isWarnEnabled() || LOGGER.isWarnEnabled(MARKER.JOBS))) {
        setupExecutionContext(job);
        LOGGER.warn(
            MARKER.JOBS,
            "{}{} has been waiting{}, no processor of this instance takes the job kind '{}'",
            job.label(),
            describe(job),
            since(job.createdAt()),
            job.kind());
      }
    }
  }

  // By convention, the kinds of the partial jobs of a job start with the kind of the job, e.g.
  // "oap:echo:setup" for the job kind "oap:echo"; jobs of workflows may have others.
  private boolean canProcess(Job job) {
    String prefix = job.kind() + ":";
    return processedKinds.stream().anyMatch(kind -> kind.startsWith(prefix));
  }

  // the description of a job with its id, so that the messages of jobs running at the same time,
  // e.g. of several searches of one API, can be told apart
  private static String describe(Job job) {
    return job.description() + ", job " + job.id();
  }

  private static String since(Long epochMillis) {
    return Objects.nonNull(epochMillis) && epochMillis > 0
        ? " since " + Instant.ofEpochMilli(epochMillis)
        : "";
  }

  private void logJobStatus(Job j, boolean queuedBefore) {
    if (LOGGER.isInfoEnabled() || LOGGER.isInfoEnabled(MARKER.JOBS)) {
      setupExecutionContext(j);

      switch (j.status()) {
        case ACCEPTED -> logJobAccepted(j, queuedBefore);
        case RUNNING -> logJobRunning(j, queuedBefore);
        case SUCCESSFUL ->
            LOGGER.info(MARKER.JOBS, "{} completed successfully{}", j.label(), describe(j));
        case FAILED ->
            LOGGER.warn(
                MARKER.JOBS,
                "{} failed{}: {}",
                j.label(),
                describe(j),
                String.join(" ", j.errors()));
        case DISMISSED -> LOGGER.info(MARKER.JOBS, "{} was cancelled{}", j.label(), describe(j));
        default -> {}
      }
    }
  }

  @SuppressWarnings({"PMD.GuardLogStatement"})
  private void logJobAccepted(Job j, boolean queuedBefore) {
    if (queuedBefore) {
      LOGGER.info(MARKER.JOBS, "{} waiting{}{}", j.label(), since(j.createdAt()), describe(j));
    } else {
      LOGGER.info(MARKER.JOBS, "{} scheduled{}", j.label(), describe(j));
    }
  }

  @SuppressWarnings({"PMD.GuardLogStatement"})
  private void logJobRunning(Job j, boolean queuedBefore) {
    if (queuedBefore) {
      LOGGER.info(MARKER.JOBS, "{} running{}{}", j.label(), since(j.startedAt()), describe(j));
    } else {
      LOGGER.info(MARKER.JOBS, "{} started{}", j.label(), describe(j));
    }
  }

  @SuppressWarnings({"PMD.GuardLogStatement"})
  private void logActiveJobSetProgress(List<Job> jobs) {
    if (logJobsDebug()) {
      jobs.stream()
          .filter(JobsImpl::isActive)
          .forEach(
              job -> {
                setupExecutionContext(job);
                LOGGER.debug(
                    MARKER.JOBS,
                    "{} at {}%{}",
                    job.label(),
                    job.progress().percent(),
                    describe(job));
              });
    }
    /*if (logJobsTrace()) {
      LOGGER.trace(
          MARKER.JOBS, "Job processor threads busy: {}/{}", activeThreads.get(), maxThreads);
    }*/
  }

  private static boolean isActive(Job job) {
    return isActive(job.status());
  }

  private static boolean isActive(Status status) {
    return status == Status.ACCEPTED || status == Status.RUNNING;
  }

  private void logJobProcessingStart(PartialJob job) {
    if (logJobsTrace()) {
      LOGGER.trace(MARKER.JOBS, "Processing job: {}", job);
    }
  }

  @SuppressWarnings({"PMD.GuardLogStatement"})
  private void logJobProcessingEnd(JobResult result, Instant start) {
    if (logJobsTrace()) {
      if (result.status() == Result.ONHOLD) {
        LOGGER.trace(MARKER.JOBS, "Postponed job: {}", result);
      } else {
        long duration = Instant.now().toEpochMilli() - start.toEpochMilli();
        LOGGER.trace(MARKER.JOBS, "Processed job in {}: {}", pretty(duration), result);
      }
    }
  }

  private static boolean logJobsTrace() {
    return LOGGER.isDebugEnabled(MARKER.JOBS) || LOGGER.isTraceEnabled();
  }

  private static boolean logJobsDebug() {
    return LOGGER.isDebugEnabled(MARKER.JOBS) || LOGGER.isDebugEnabled();
  }

  private static String pretty(long milliseconds) {
    Duration d = Duration.ofSeconds(milliseconds / 1000);
    return AmountFormats.wordBased(d, Locale.ENGLISH);
  }
}
