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
import de.ii.xtraplatform.base.domain.AppConfiguration;
import de.ii.xtraplatform.base.domain.AppContext;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@AutoBind
public class ProjLoaderImpl implements ProjLoader {

  private final Path dataDirectory;

  @Inject
  public ProjLoaderImpl(AppContext appContext) {
    this.dataDirectory = getDataDirectory(appContext.getDataDir(), appContext.getConfiguration());
  }

  // for unit tests only
  ProjLoaderImpl(Path dataDirectory) {
    this.dataDirectory = dataDirectory;
  }

  @Override
  public String getName() {
    return "proj-9.1.0-0";
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
    return ImmutableMap.of(
        dataDirectory,
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
  public void preload() {
    // sqLiteLoader.load();
  }

  @Override
  public Path getDataDirectory() {
    return dataDirectory;
  }

  private Path getDataDirectory(Path dataDir, AppConfiguration configuration) {
    String projLocation = configuration.proj.location;
    if (Paths.get(projLocation).isAbsolute()) {
      return Paths.get(projLocation);
    }

    return dataDir.resolve(projLocation);
  }
}
