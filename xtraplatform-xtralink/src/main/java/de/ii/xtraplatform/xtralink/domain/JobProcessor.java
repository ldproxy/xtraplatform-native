/*
 * Copyright 2026 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.xtralink.domain;

import de.ii.xtralink.jobs.Job;
import de.ii.xtralink.jobs.PartialJob;

public interface JobProcessor<T extends JobInputs, U extends JobContext> extends JobProcessorBase {

  Class<T> getInputsClass();

  Class<U> getPartialContextClass();

  default T getInputs(Job job, JobProcessing jobs) {
    return jobs.getInputs(job, getInputsClass());
  }

  default U getPartialContext(PartialJob job, JobProcessing jobs) {
    return jobs.getContext(job, getPartialContextClass());
  }
}
