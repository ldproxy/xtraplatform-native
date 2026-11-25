/*
 * Copyright 2026 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.xtralink.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.azahnen.dagger.annotations.AutoBind;
import de.ii.xtralink.jobs.Job;
import de.ii.xtralink.jobs.PartialJob;
import de.ii.xtralink.jobs.internal.JobQueue;
import de.ii.xtraplatform.base.domain.Jackson;
import de.ii.xtraplatform.ops.domain.OpsEndpoint;
import de.ii.xtraplatform.xtralink.domain.Jobs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api/jobs")
@Singleton
@AutoBind
@SuppressWarnings({"PMD.AvoidCatchingGenericException"})
public class OpsEndpointJobs implements OpsEndpoint {

  private static final Logger LOGGER = LoggerFactory.getLogger(OpsEndpointJobs.class);

  private final Jobs jobs;
  private final ObjectMapper objectMapper;

  public static class JobResponse {
    public List<Job> sets;
    public List<PartialJob> taken;
    public List<PartialJob> failed;
  }

  @Inject
  public OpsEndpointJobs(Jackson jackson, Jobs jobs) {
    this.objectMapper = jackson.getDefaultObjectMapper();
    this.jobs = jobs;
  }

  @Override
  public String getEntrypoint() {
    return "jobs";
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Get all jobs",
      description =
          "Returns all jobs along with their progress. With `debug`, also returns the taken and failed partial jobs.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Successful operation",
        content =
            @Content(
                mediaType = "application/json",
                schema =
                    @Schema(
                        implementation = JobResponse.class,
                        example = "{\n  \"sets\" : [ ]\n}"))),
    @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response getJobs(@QueryParam("debug") boolean debug) throws JsonProcessingException {
    try {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sets", jobs.list());

      if (debug) {
        response.put("taken", jobs.taken());
        response.put("failed", jobs.failed());
      }

      return Response.ok(objectMapper.writeValueAsString(response)).build();
    } catch (Throwable e) {
      LOGGER.error("Error while serializing jobs", e);
      throw e;
    }
  }

  @POST
  @Path("/rpc")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Job queue RPC",
      description =
          "JSON-RPC 2.0 access to the job queue for remote xtralink executors (queue `rpc` with"
              + " `rpc.url` pointing here). Only answered with jobs queue `LOCAL`; with `REDIS`,"
              + " every request gets a JSON-RPC error, since executors reach Redis directly.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "JSON-RPC response, result or error"),
  })
  public Response rpc(String request) {
    return Response.ok(JobQueue.rpc(request)).build();
  }
}
