/*
 * Copyright 2026 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.xtralink.domain;

public class JobProcessingException extends Exception {

  public JobProcessingException(String message) {
    super(message);
  }

  public JobProcessingException(Throwable cause) {
    super(cause);
  }

  public JobProcessingException(String message, Throwable cause) {
    super(message, cause);
  }
}
