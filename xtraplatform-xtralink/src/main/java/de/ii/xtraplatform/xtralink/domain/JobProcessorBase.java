/*
 * Copyright 2026 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.xtralink.domain;

import com.github.azahnen.dagger.annotations.AutoMultiBind;
import de.ii.xtralink.jobs.Job;
import de.ii.xtralink.jobs.JobResult;
import de.ii.xtralink.jobs.PartialJob;
import java.util.Set;

@AutoMultiBind
public interface JobProcessorBase {

  /**
   * Returns the kinds of jobs that this processor can handle. This method is used to identify the
   * types of jobs that the processor is responsible for.
   *
   * @return a set of job kinds
   */
  Set<String> getKinds();

  /**
   * Returns the priority of this job processor. Higher priority processors are preferred over lower
   * priority ones by the job runner.
   *
   * @return the priority of the processor
   */
  int getPriority();

  /**
   * Processes a partial job. This method is called to perform the actual work of the job processor.
   *
   * @param partialJob the partial job to process
   * @param job the full job that the partial job is part of
   * @param jobs the job processing context
   * @return the result of processing the job
   * @throws Exception if an error occurs during processing
   */
  JobResult process(PartialJob partialJob, Job job, JobProcessing jobs) throws Exception;
}
