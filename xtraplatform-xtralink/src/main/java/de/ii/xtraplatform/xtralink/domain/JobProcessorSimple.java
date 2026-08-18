package de.ii.xtraplatform.xtralink.domain;

import de.ii.xtralink.jobs.Job;
import de.ii.xtralink.jobs.JobResult;
import de.ii.xtralink.jobs.PartialJob;
import de.ii.xtraplatform.xtralink.domain.JobContext.JobContextEntity;
import java.util.Locale;
import java.util.Set;

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
    return Set.of(getKind());
  }

  @Override
  public final JobResult process(PartialJob partialJob, Job job, JobProcessing jobs)
      throws Exception {
    T inputs = getInputs(job, jobs);
    Phase phase = getPhase(partialJob);

    return switch (phase) {
      case SETUP -> setup(partialJob, job, inputs, jobs);
      case EXECUTE -> execute(partialJob, job, inputs, jobs);
      case CLEANUP -> cleanup(partialJob, job, inputs, jobs);
    };
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
