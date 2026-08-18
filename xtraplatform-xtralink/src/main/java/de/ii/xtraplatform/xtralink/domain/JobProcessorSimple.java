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
import de.ii.xtraplatform.xtralink.domain.JobContext.JobContextEntity;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class JobProcessorSimple<T extends JobInputs>
    implements JobProcessor<T, JobContextEntity> {

  public enum Phase {
    SETUP,
    EXECUTE,
    CLEANUP;
  }

  public abstract String getKind();

  public abstract JobResult setup(PartialJob partialJob, Job job, T inputs, JobProcessing jobs);

  public abstract JobResult execute(PartialJob partialJob, Job job, T inputs, JobProcessing jobs);

  public abstract JobResult cleanup(PartialJob partialJob, Job job, T inputs, JobProcessing jobs);

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
    return String.join(":", getKind(), String.join(":", phase.name().toLowerCase(Locale.ROOT)));
  }

  private Phase getPhase(PartialJob partialJob) {
    String kind = partialJob.kind();
    if (kind.endsWith(":" + Phase.SETUP.name().toLowerCase(Locale.ROOT))) {
      return Phase.SETUP;
    } else if (kind.endsWith(":" + Phase.EXECUTE.name().toLowerCase(Locale.ROOT))) {
      return Phase.EXECUTE;
    } else if (kind.endsWith(":" + Phase.CLEANUP.name().toLowerCase(Locale.ROOT))) {
      return Phase.CLEANUP;
    }
    throw new IllegalArgumentException("Unknown phase for kind: " + kind);
  }
}
