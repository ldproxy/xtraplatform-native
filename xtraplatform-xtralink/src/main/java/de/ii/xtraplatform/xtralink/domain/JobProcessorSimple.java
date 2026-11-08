/*
 * Copyright 2026 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.xtralink.domain;

import de.ii.xtralink.jobs.Job;
import de.ii.xtralink.jobs.JobResult;
import de.ii.xtralink.jobs.PartialJob;
import de.ii.xtraplatform.base.domain.LogContext;
import de.ii.xtraplatform.blobs.domain.ResourceStore;
import de.ii.xtraplatform.xtralink.domain.JobContext.JobContextEntity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class JobProcessorSimple<T extends JobInputs>
    implements JobProcessor<T, JobContextEntity> {

  private static final Logger LOGGER = LoggerFactory.getLogger(JobProcessorSimple.class);

  public enum Phase {
    SETUP,
    EXECUTE,
    CLEANUP,
    FINALIZE;

    public String getPartialKind(String kind) {
      return kind + getSuffix();
    }

    public String getSuffix() {
      return ":" + name().toLowerCase(Locale.ROOT);
    }

    public static Phase fromPartialKind(String partialKind) {
      if (partialKind.endsWith(SETUP.getSuffix())) {
        return SETUP;
      } else if (partialKind.endsWith(EXECUTE.getSuffix())) {
        return EXECUTE;
      } else if (partialKind.endsWith(CLEANUP.getSuffix())) {
        return CLEANUP;
      } else if (partialKind.endsWith(FINALIZE.getSuffix())) {
        return FINALIZE;
      }
      throw new IllegalArgumentException("Unknown phase for kind: " + partialKind);
    }
  }

  // Markers mirror the output paths under a per-job folder, so finalize finds the files of a job
  // on any instance and after restarts, and parallel partial jobs never write the same marker.
  private static final Path OUTPUT_MARKERS = Path.of(".outputs");

  private final String kind;
  private final ResourceStore jobStore;

  protected JobProcessorSimple(String kind, ResourceStore resourceStore) {
    this.kind = kind;
    this.jobStore = resourceStore.with(Jobs.RESOURCE_TYPE, kind);
  }

  /**
   * Returns the resource store associated with this job processor. The resource store is used to
   * manage resources related to the job processing, such as temporary files or input and output
   * data.
   *
   * @return the resource store
   */
  protected final ResourceStore getStore() {
    return jobStore;
  }

  /**
   * Saves an output file to the resource store. Files that are saved using this method will be
   * automatically deleted during the finalize phase of the job processing, which is run when a job
   * is deleted from the queue. This method should be used to store any output files generated
   * during the execution of the job
   *
   * @param job the job the output file belongs to
   * @param path the path of the output file
   * @param content the content of the output file as an InputStream
   * @throws IOException if an I/O error occurs while saving the file
   */
  protected final void saveOutputFile(Job job, Path path, InputStream content) throws IOException {
    jobStore.put(outputMarkers(job).resolve(path), InputStream.nullInputStream());
    jobStore.put(path, content);
  }

  /**
   * Called for the setup phase of the job processing. This method is responsible for preparing the
   * job for execution, including any necessary initialization or configuration steps. The partials
   * of the job, usually just the execute phase, are queued by xtralink once setup succeeded.
   *
   * @param partialJob
   * @param job
   * @param inputs
   * @param jobs
   * @return
   */
  public abstract JobResult setup(PartialJob partialJob, Job job, T inputs, JobProcessing jobs);

  /**
   * Called for the execution phase of the job processing. This method is responsible for performing
   * the main work of the job.
   *
   * @param partialJob
   * @param job
   * @param inputs
   * @param jobs
   * @return
   */
  public abstract JobResult execute(PartialJob partialJob, Job job, T inputs, JobProcessing jobs);

  /**
   * Called for the cleanup phase of the job processing. This method is responsible for cleaning up
   * any resources or temporary data used during the execution of the job.
   *
   * @param partialJob
   * @param job
   * @param inputs
   * @param jobs
   * @return
   */
  public abstract JobResult cleanup(PartialJob partialJob, Job job, T inputs, JobProcessing jobs);

  /**
   * Called for the finalize phase of the job processing. This method is responsible for finalizing
   * the job before deletion, especially removing any result files that are referenced from the job
   * outputs.
   *
   * @param partialJob
   * @param job
   * @param inputs
   * @param jobs
   * @return
   */
  private final JobResult finalize(PartialJob partialJob, Job job, T inputs, JobProcessing jobs) {
    Path markers = outputMarkers(job);
    List<Path> outputFiles;
    try (Stream<Path> entries =
        jobStore.walk(markers, Integer.MAX_VALUE, (path, attributes) -> attributes.isValue())) {
      outputFiles = entries.filter(path -> !path.toString().isEmpty()).toList();
    } catch (IOException e) {
      return unexpectedError(job, Phase.FINALIZE, e, jobs);
    }

    for (Path path : outputFiles) {
      try {
        jobStore.delete(path);
        jobStore.delete(markers.resolve(path));
      } catch (IOException e) {
        return unexpectedError(job, Phase.FINALIZE, e, jobs);
      }
    }
    deleteDirectories(markers);
    return jobs.success();
  }

  /**
   * Deletes the output files of all jobs, if the queue does not survive a restart: no job of an
   * earlier run exists any more, so the finalize phase would never run for them.
   */
  @Override
  public void beforeQueueStart(boolean persistentQueue) {
    if (persistentQueue) {
      return;
    }

    List<Path> markers;
    try (Stream<Path> entries =
        jobStore.walk(
            OUTPUT_MARKERS,
            Integer.MAX_VALUE,
            (path, attributes) -> attributes.isValue() && !attributes.isHidden())) {
      // a marker is <job id>/<output path>
      markers = entries.filter(path -> path.getNameCount() > 1).toList();
    } catch (IOException e) {
      LOGGER.warn("Could not list the output files of jobs of kind {}: {}", kind, e.getMessage());
      return;
    }

    int deleted = 0;
    for (Path marker : markers) {
      Path path = marker.subpath(1, marker.getNameCount());
      try {
        jobStore.delete(path);
        jobStore.delete(OUTPUT_MARKERS.resolve(marker));
        deleted++;
      } catch (IOException e) {
        LOGGER.warn("Could not delete the output file {} of a job: {}", path, e.getMessage());
      }
    }
    deleteDirectories(OUTPUT_MARKERS);

    if (deleted > 0) {
      LOGGER.info("Deleted {} output files of jobs of kind {} of an earlier run", deleted, kind);
    }
  }

  // Deletes a marker folder and its sub folders, deepest first; a folder that is not empty stays.
  private void deleteDirectories(Path root) {
    List<Path> directories;
    try (Stream<Path> entries =
        jobStore.walk(root, Integer.MAX_VALUE, (path, attributes) -> !attributes.isValue())) {
      directories =
          entries
              .filter(path -> !path.toString().isEmpty())
              .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
              .toList();
    } catch (IOException e) {
      return;
    }
    for (Path directory : directories) {
      deleteQuietly(root.resolve(directory));
    }
    deleteQuietly(root);
  }

  private void deleteQuietly(Path path) {
    try {
      jobStore.delete(path);
    } catch (IOException e) {
      // not empty or already gone
    }
  }

  private static Path outputMarkers(Job job) {
    return OUTPUT_MARKERS.resolve(job.id());
  }

  @Override
  public int getPriority() {
    return 1000;
  }

  @Override
  public final Set<String> getKinds() {
    return Arrays.stream(Phase.values()).map(this::partialKind).collect(Collectors.toSet());
  }

  @Override
  public final JobResult process(PartialJob partialJob, Job job, JobProcessing jobs)
      throws Exception {
    T inputs = getInputs(job, jobs);
    Phase phase = Phase.fromPartialKind(partialJob.kind());

    try {
      return switch (phase) {
        case SETUP -> setup(partialJob, job, inputs, jobs);
        case EXECUTE -> execute(partialJob, job, inputs, jobs);
        case CLEANUP -> cleanup(partialJob, job, inputs, jobs);
        case FINALIZE -> finalize(partialJob, job, inputs, jobs);
      };
    } catch (Throwable e) {
      // a processor may stop a dismissed job with an exception
      if (jobs.isCancelled(job.id())) {
        return jobs.failure("The job was cancelled");
      }

      return unexpectedError(job, phase, e, jobs);
      // TODO: passing errors through ffi to go does not work as expected
      // throw new JobProcessingException("Error processing job " + job.id() + " in phase " + phase,
      // e);
    }
  }

  /**
   * The job errors are public, so the details of an unexpected error, e.g. paths of the job store,
   * are only logged; the job reports the error ID under which they can be found in the log.
   */
  private static JobResult unexpectedError(Job job, Phase phase, Throwable e, JobProcessing jobs) {
    long errorId = ThreadLocalRandom.current().nextLong();
    LogContext.error(
        LOGGER,
        e,
        "Error processing job {} in phase {}, error ID {}",
        job.id(),
        phase,
        String.format("%016x", errorId));
    return jobs.failure(
        String.format(
            "Error processing job %s in phase %s, it has been logged. Error ID: %016x",
            job.id(), phase, errorId));
  }

  @Override
  public final Class<JobContextEntity> getPartialContextClass() {
    return JobContextEntity.class;
  }

  private String partialKind(Phase phase) {
    return phase.getPartialKind(kind);
  }
}
