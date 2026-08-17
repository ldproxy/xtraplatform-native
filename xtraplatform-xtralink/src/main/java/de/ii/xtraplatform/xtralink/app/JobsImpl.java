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
import de.ii.xtralink.jobs.JobProgress;
import de.ii.xtralink.jobs.JobResult;
import de.ii.xtralink.jobs.PartialJob;
import de.ii.xtralink.jobs.PartialJobConfiguration;
import de.ii.xtralink.jobs.QueueConfiguration;
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
  private final Map<String, JobProgress> jobProgress;
  private final ScheduledExecutorService polling;

  @Inject
  JobsImpl(AppContext appContext, Jackson jackson, Lazy<Set<JobProcessorBase>> processors) {
    this.appContext = appContext;
    this.processors = processors;
    this.registrations = new CopyOnWriteArrayList<>();
    this.objectMapper =
        jackson.getDefaultObjectMapper().registerModule(DESERIALIZE_IMMUTABLE_BUILDER_NESTED);
    this.jobStatus = new ConcurrentHashMap<>();
    this.jobProgress = new ConcurrentHashMap<>();

    this.polling =
        MoreExecutors.getExitingScheduledExecutorService(
            (ScheduledThreadPoolExecutor)
                Executors.newScheduledThreadPool(
                    1, new ThreadFactoryBuilder().setNameFormat("jobs.poll-%d").build()));
  }

  @Override
  public CompletionStage<Void> onStart(boolean isStartupAsync) {
    new XtralinkLoaderImpl().load();

    Set<JobProcessorBase> procs = processors.get();
    if (Objects.nonNull(procs)) {
      procs.forEach(this::register);
    }

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

    polling.scheduleAtFixedRate(this::logActiveJobSetProgress, 5, 5, TimeUnit.SECONDS);

    return AppLifeCycle.super.onStart(isStartupAsync);
  }

  @Override
  public void onStop() {
    registrations.forEach(Registration::close);

    JobQueue.stop();
  }

  @SuppressWarnings({"PMD.GuardLogStatement"})
  private void logActiveJobSetProgress() {
    if (logJobsDebug()) {
      jobProgress.forEach(
          (jobSetId, progress) -> {
            Optional<Job> jobSet = JobQueue.get(jobSetId);
            if (jobSet.isEmpty()) {
              return;
            }

            setupExecutionContext(jobSet.get());

            if (logJobsDebug()) {
              LOGGER.debug(
                  MARKER.JOBS,
                  "{} at {}%{}",
                  jobSet.get().label(),
                  jobSet.get().progress().percent(),
                  jobSet.get().description());
            }
          });
    }
    /*if (logJobsTrace()) {
      LOGGER.trace(
          MARKER.JOBS, "Job processor threads busy: {}/{}", activeThreads.get(), maxThreads);
    }*/
  }

  private static boolean logJobsTrace() {
    return LOGGER.isDebugEnabled(MARKER.JOBS) || LOGGER.isTraceEnabled();
  }

  private static boolean logJobsDebug() {
    return LOGGER.isDebugEnabled(MARKER.JOBS) || LOGGER.isDebugEnabled();
  }

  @Override
  public CompletableFuture<Job> push(JobConfiguration job, JobListener onChange) {
    LOGGER.info("JOBS: Pushing job: {}", job.label());

    JobListener onProgress2 =
        (j) -> {
          if (j.status() == Status.RUNNING
              && jobStatus.containsKey(j.id())
              && jobStatus.get(j.id()) == Status.ACCEPTED) {
            if (LOGGER.isInfoEnabled() || LOGGER.isInfoEnabled(MARKER.JOBS)) {
              LOGGER.info(MARKER.JOBS, "{} started{}", j.label(), j.description());
            }
          }
          if (j.status() == Status.ACCEPTED || j.status() == Status.RUNNING) {
            jobStatus.put(j.id(), j.status());
            jobProgress.put(j.id(), j.progress());
          } else {
            jobStatus.remove(j.id());
            jobProgress.remove(j.id());
          }

          onChange.onProgress(j);
        };

    return JobQueue.push(job, onProgress2);
  }

  @Override
  public CompletableFuture<PartialJob> push(PartialJobConfiguration partialJob) {
    // LOGGER.info("JOBS: Pushing partial job: {}", partialJob.kind());
    return JobQueue.pushPartial(partialJob);
  }

  @Override
  public CompletableFuture<PartialJob> repush(String id) {
    // LOGGER.info("JOBS: Re-Pushing partial job: {}", id);
    return JobQueue.repushPartial(id);
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

  private void register(JobProcessorBase processor) {
    de.ii.xtralink.jobs.internal.JobProcessor processorWrapper =
        (partialJob, job) -> {
          Instant start = Instant.now();
          setupExecutionContext(job);
          logJobProcessingStart(partialJob);
          JobResult result = processor.process(partialJob, job, this);
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

  private void setupExecutionContext(Job job) {
    if (JobContextEntity.is(job.context())) {
      LogContext.put(LogContext.CONTEXT.SERVICE, JobContextEntity.from(job.context()));
    }
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

  private static String pretty(long milliseconds) {
    Duration d = Duration.ofSeconds(milliseconds / 1000);
    return AmountFormats.wordBased(d, Locale.ENGLISH);
  }
}
