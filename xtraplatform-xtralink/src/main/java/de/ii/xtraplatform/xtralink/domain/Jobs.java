/*
 * Copyright 2026 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.xtralink.domain;

import static de.ii.xtraplatform.base.domain.util.JacksonModules.DESERIALIZE_IMMUTABLE_BUILDER_NESTED;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import de.ii.xtralink.jobs.Job;
import de.ii.xtralink.jobs.JobConfiguration;
import de.ii.xtralink.jobs.JobProgress;
import de.ii.xtralink.jobs.PartialJobConfiguration;
import de.ii.xtralink.jobs.ProgressUpdate;
import de.ii.xtralink.jobs.internal.JobListener;
import de.ii.xtraplatform.base.domain.JacksonProvider.IntervalMixin;
import io.dropwizard.jackson.CaffeineModule;
import io.dropwizard.jackson.FuzzyEnumModule;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.threeten.extra.Interval;

public interface Jobs {

  // TODO: move to Jackson
  ObjectMapper DEFAULT_MAPPER =
      new ObjectMapper()
          .disable(SerializationFeature.INDENT_OUTPUT)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .setSerializationInclusion(JsonInclude.Include.NON_ABSENT)
          .registerModule(new Jdk8Module())
          .registerModule(new GuavaModule())
          .registerModule(new CaffeineModule())
          .registerModule(new FuzzyEnumModule())
          .registerModule(new JavaTimeModule())
          .addMixIn(Interval.class, IntervalMixin.class)
          .setDefaultMergeable(false)
          .registerModule(new AfterburnerModule())
          .registerModule(DESERIALIZE_IMMUTABLE_BUILDER_NESTED);
  TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  // TODO: add various methods or builder
  static JobConfiguration create(
      String type,
      int priority,
      String label,
      String description,
      Object inputs,
      Object context,
      Map<String, Object> progressDetails) {

    try {
      byte[] inputsBytes = DEFAULT_MAPPER.writeValueAsBytes(inputs);
      Map<String, Object> inputsMap = DEFAULT_MAPPER.readValue(inputsBytes, MAP_TYPE);
      byte[] contextBytes = DEFAULT_MAPPER.writeValueAsBytes(context);
      Map<String, Object> contextMap = DEFAULT_MAPPER.readValue(contextBytes, MAP_TYPE);

      return new JobConfiguration(
          type,
          priority,
          label,
          description,
          inputsMap,
          contextMap,
          new JobProgress(0, 0, 0, progressDetails),
          true,
          true,
          List.of());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  static JobConfiguration addFollowUps(JobConfiguration job, JobConfiguration... followUps) {
    return new de.ii.xtralink.jobs.JobConfiguration(
        job.kind(),
        job.priority(),
        job.label(),
        job.description(),
        job.inputs(),
        job.context(),
        job.progress(),
        job.setup(),
        job.cleanup(),
        List.of(followUps));
  }

  static PartialJobConfiguration createPartial(
      String type,
      int priority,
      String partOf,
      Object context,
      int progressTotal,
      List<ProgressUpdate> progressUpdates) {
    try {
      byte[] contextBytes = DEFAULT_MAPPER.writeValueAsBytes(context);
      Map<String, Object> contextMap = DEFAULT_MAPPER.readValue(contextBytes, MAP_TYPE);

      return new PartialJobConfiguration(
          type,
          priority,
          partOf,
          new JobProgress(0, progressTotal, 0, null),
          progressUpdates,
          Optional.empty(),
          contextMap);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  CompletableFuture<Job> push(JobConfiguration job, JobListener onChange);

  default CompletableFuture<Job> push(JobConfiguration job) {
    return push(job, j -> {});
  }
}
