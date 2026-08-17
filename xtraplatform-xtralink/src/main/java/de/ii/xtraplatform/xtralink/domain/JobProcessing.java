/*
 * Copyright 2026 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.xtralink.domain;

import de.ii.xtralink.jobs.BaseJob;
import de.ii.xtralink.jobs.Identifiers.Result;
import de.ii.xtralink.jobs.Job;
import de.ii.xtralink.jobs.JobResult;
import de.ii.xtralink.jobs.PartialJob;
import de.ii.xtralink.jobs.PartialJobConfiguration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface JobProcessing {

  CompletableFuture<PartialJob> push(PartialJobConfiguration partialJob);

  CompletableFuture<PartialJob> repush(String id);

  void init(String jobId, int progressTotal, Map<String, ?> progressDetails);

  void update(String partialJobId, int delta);

  <T extends JobInputs> T getInputs(Job job, Class<T> contextClass);

  <T extends JobContext> T getContext(BaseJob job, Class<T> contextClass);

  default JobResult success() {
    return new JobResult(Result.SUCCESS, List.of());
  }

  default JobResult failure(String message) {
    return new JobResult(Result.FAILURE, List.of(message));
  }

  default JobResult retry(String message) {
    return new JobResult(Result.RETRY, List.of(message));
  }

  default JobResult onHold() {
    return new JobResult(Result.ONHOLD, List.of());
  }
}
