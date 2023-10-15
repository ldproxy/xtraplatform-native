/*
 * Copyright 2022 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.proj.domain;

import com.github.azahnen.dagger.annotations.AutoBind;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import de.ii.xtraplatform.base.domain.AppLifeCycle;
import de.ii.xtraplatform.base.domain.LogContext;
import de.ii.xtraplatform.blobs.domain.ResourceStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@AutoBind
public class ProjLoaderImpl implements ProjLoader, AppLifeCycle {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProjLoaderImpl.class);
  private static final String RESOURCES_PATH = "proj";

  private final ResourceStore blobStore;
  private Optional<Path> dataDirectory;

  @Inject
  public ProjLoaderImpl(ResourceStore blobStore) {
    this.blobStore = blobStore.with(RESOURCES_PATH);
    this.dataDirectory = Optional.empty();
  }

  // for unit tests only
  ProjLoaderImpl(Path dataDirectory) {
    this.dataDirectory = Optional.ofNullable(dataDirectory);
    this.blobStore = null;
  }

  @Override
  public int getPriority() {
    return 900;
  }

  @Override
  public void onStart() {
    if (dataDirectory.isEmpty() && Objects.nonNull(blobStore)) {
      try {
        this.dataDirectory = blobStore.asLocalPath(Path.of(""), true);
      } catch (IOException e) {
        LogContext.error(LOGGER, e, "Could not initialize PROJ data directory");
      }
    }
  }

  @Override
  public String getName() {
    return "proj-9.1.0-1";
  }

  @Override
  public String getLabel() {
    return "PROJ";
  }

  @Override
  public List<String> getLibraries() {
    if (System.getProperty("os.name").contains("Mac")) {
      return ImmutableList.of("libsqlite3.dylib", "libz.dylib", "libtiff.dylib", "libproj.dylib");
    }

    return ImmutableList.of("libsqlite3.so", "libz.so", "libtiff.so", "libproj.so");
  }

  @Override
  public Map<Path, List<String>> getResources() {
    if (dataDirectory.isEmpty()) {
      return ImmutableMap.of();
    }

    return ImmutableMap.of(
        dataDirectory.get(),
        ImmutableList.of(
            "CH",
            "deformation_model.schema.json",
            "GL27",
            "ITRF2000",
            "ITRF2008",
            "ITRF2014",
            "nad27",
            "nad83",
            "nad.lst",
            "other.extra",
            "proj.db",
            "proj.ini",
            "projjson.schema.json",
            "triangulation.schema.json",
            "world"));
  }

  @Override
  public Path getDataDirectory() {
    return dataDirectory.orElseThrow();
  }
}
