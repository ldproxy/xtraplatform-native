/*
 * Copyright 2026 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.xtralink.domain;

import java.util.Map;

public interface JobContext {

  class JobContextNone implements JobContext {}

  JobContext NONE = new JobContextNone();

  // NOPMD - TODO: record fails docs generation
  class JobContextEntity implements JobContext {
    public static boolean is(Map<String, Object> context) {
      return context.containsKey("entity");
    }

    public static String from(Map<String, Object> context) {
      return (String) context.getOrDefault("entity", "");
    }

    private final String entity;

    public JobContextEntity(String entity) {
      this.entity = entity;
    }

    public String getEntity() {
      return entity;
    }
  }
}
