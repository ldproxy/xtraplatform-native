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
import de.ii.xtralink.jobs.PartialJobConfiguration;
import de.ii.xtraplatform.blobs.domain.ResourceStore;
import de.ii.xtraplatform.xtralink.domain.JobContext.JobContextEntity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public abstract class JobProcessorSimple<T extends JobInputs>
    implements JobProcessor<T, JobContextEntity> {

  public enum Phase {
    SETUP,
    EXECUTE,
    CLEANUP,
    FINALIZE;
  }

  private final String kind;
  private final ResourceStore jobStore;
  private final List<Path> outputFiles;

  protected JobProcessorSimple(String kind, ResourceStore resourceStore) {
    this.kind = kind;
    this.jobStore = resourceStore.with(Jobs.RESOURCE_TYPE, kind);
    this.outputFiles = new CopyOnWriteArrayList<>();
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
   * @param path the path of the output file
   * @param content the content of the output file as an InputStream
   * @throws IOException if an I/O error occurs while saving the file
   */
  protected final void saveOutputFile(Path path, InputStream content) throws IOException {
    outputFiles.add(path);
    jobStore.put(path, content);
  }

  /**
   * Called for the setup phase of the job processing. This method is responsible for preparing the
   * job for execution, including any necessary initialization or configuration steps.
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
  private JobResult finalize(PartialJob partialJob, Job job, T inputs, JobProcessing jobs) {
    for (Path path : outputFiles) {
      try {
        jobStore.delete(path);
      } catch (IOException e) {
        return jobs.failure(
            "Error deleting output file " + path + " for job " + job.id() + ": " + e.getMessage());
      }
    }
    return jobs.success();
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
    Phase phase = getPhase(partialJob);

    try {
      return switch (phase) {
        case SETUP -> presetup(partialJob, job, inputs, jobs);
        case EXECUTE -> execute(partialJob, job, inputs, jobs);
        case CLEANUP -> cleanup(partialJob, job, inputs, jobs);
        case FINALIZE -> finalize(partialJob, job, inputs, jobs);
      };
    } catch (Throwable e) {
      return jobs.failure(
          "Error processing job " + job.id() + " in phase " + phase + ": " + e.getMessage());
      // TODO: passing errors through ffi to go does not work as expected
      // throw new JobProcessingException("Error processing job " + job.id() + " in phase " + phase,
      // e);
    }
  }

  private JobResult presetup(PartialJob partialJob, Job job, T inputs, JobProcessing jobs) {
    jobs.init(job.id(), 1, null);

    JobResult setupResult = setup(partialJob, job, inputs, jobs);

    PartialJobConfiguration partial =
        Jobs.createPartial(
            partialKind(Phase.EXECUTE), job.priority(), job.id(), job.context(), 1, List.of());
    jobs.push(partial);

    return setupResult;
  }

  @Override
  public final Class<JobContextEntity> getPartialContextClass() {
    return JobContextEntity.class;
  }

  private String partialKind(Phase phase) {
    return String.join(":", kind, String.join(":", phase.name().toLowerCase(Locale.ROOT)));
  }

  private Phase getPhase(PartialJob partialJob) {
    String kind = partialJob.kind();
    if (kind.endsWith(":" + Phase.SETUP.name().toLowerCase(Locale.ROOT))) {
      return Phase.SETUP;
    } else if (kind.endsWith(":" + Phase.EXECUTE.name().toLowerCase(Locale.ROOT))) {
      return Phase.EXECUTE;
    } else if (kind.endsWith(":" + Phase.CLEANUP.name().toLowerCase(Locale.ROOT))) {
      return Phase.CLEANUP;
    } else if (kind.endsWith(":" + Phase.FINALIZE.name().toLowerCase(Locale.ROOT))) {
      return Phase.FINALIZE;
    }
    throw new IllegalArgumentException("Unknown phase for kind: " + kind);
  }
}
