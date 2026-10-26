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

  private final AppContext appContext;
  private final Lazy<Set<JobProcessorBase>> processors;
  private final List<Registration> registrations;
  private final ObjectMapper objectMapper;
  private final Map<String, Status> jobStatus;
  private final Map<String, JobListener.Registration> jobRegistration;
  private final Map<String, ActiveJob> activeJobs;
  private final ScheduledExecutorService polling;
  private CancelListener.Registration cancelRegistration;

  @Inject
  JobsImpl(AppContext appContext, Jackson jackson, Lazy<Set<JobProcessorBase>> processors) {
    this.appContext = appContext;
    this.processors = processors;
    this.registrations = new CopyOnWriteArrayList<>();
    this.objectMapper =
        jackson.getDefaultObjectMapper().registerModule(DESERIALIZE_IMMUTABLE_BUILDER_NESTED);
    this.jobStatus = new ConcurrentHashMap<>();
    this.jobRegistration = new ConcurrentHashMap<>();
    this.activeJobs = new ConcurrentHashMap<>();
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

    Set<JobProcessorBase> procs = processors.get();
    if (Objects.nonNull(procs)) {
      procs.forEach(this::register);
    }

    this.cancelRegistration = CancelListener.register(this::onCancel);
    JobQueue.onCancel(cancelRegistration);

    QueueConfiguration queueConfiguration =
        new QueueConfiguration(
            appContext.getConfiguration().getJobConcurrency(),
            appContext.getInstanceName(),
            appContext.getConfiguration().getJobs().getQueue() == QUEUE.REDIS
                ? Queue.REDIS
                : Queue.LOCAL,
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

          if (!isActive(j)) {
            closeRegistration(j);
          }

          onChange.onProgress(j);
        };
    JobListener.Registration registration = JobListener.register(onProgress2);

    Job pushedJob = JobQueue.push(job, registration);

    jobRegistration.put(pushedJob.id(), registration);
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
        // LOGGER.info("JOBS: Registered job processor for kind: {}", kind);
      } catch (Throwable e) {
        LOGGER.error("JOBS: Failed to register job processor for kind: {}", kind, e);
        registration.close();
      }
    }
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
  // REDIS queue have no listener, polling is the only source for them.
  private synchronized void onJobUpdate(Job job) {
    Status previous = jobStatus.get(job.id());
    if (previous != null && stage(job.status()) <= stage(previous)) {
      return;
    }
    jobStatus.put(job.id(), job.status());
    logJobStatus(job);
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

      for (Job job : jobs) {
        listed.add(job.id());
        // finished jobs never seen active are history, e.g. from before a restart
        if (isActive(job) || jobStatus.containsKey(job.id())) {
          onJobUpdate(job);
        }
      }
      jobStatus.entrySet().removeIf(e -> !isActive(e.getValue()) && !listed.contains(e.getKey()));

      logActiveJobSetProgress(jobs);
    } catch (Throwable e) {
      LogContext.error(LOGGER, e, "Error while polling jobs");
    }
  }

  private void logJobStatus(Job j) {
    if (LOGGER.isInfoEnabled() || LOGGER.isInfoEnabled(MARKER.JOBS)) {
      setupExecutionContext(j);

      switch (j.status()) {
        case ACCEPTED -> LOGGER.info(MARKER.JOBS, "{} scheduled{}", j.label(), j.description());
        case RUNNING -> LOGGER.info(MARKER.JOBS, "{} started{}", j.label(), j.description());
        case SUCCESSFUL ->
            LOGGER.info(MARKER.JOBS, "{} completed successfully{}", j.label(), j.description());
        case FAILED -> LOGGER.info(MARKER.JOBS, "{} failed{}", j.label(), j.description());
        case DISMISSED ->
            LOGGER.info(MARKER.JOBS, "{} was cancelled{}", j.label(), j.description());
        default -> {}
      }
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
                    job.description());
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
