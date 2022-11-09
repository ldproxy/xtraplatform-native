/*
 * Copyright 2022 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.spatialite.domain;

import com.github.azahnen.dagger.annotations.AutoBind;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import de.ii.xtraplatform.nativ.loader.domain.XtraplatformNative;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@AutoBind
public class SpatiaLiteLoaderImpl implements SpatiaLiteLoader {

  @Inject
  public SpatiaLiteLoaderImpl() {}

  @Override
  public String getName() {
    return "spatialite-5.0.1-2";
  }

  @Override
  public String getLabel() {
    return "SpatiaLite";
  }

  @Override
  public List<String> getLibraries() {
    if (System.getProperty("os.name").contains("Mac")) {
      return ImmutableList.of("libz.dylib", "libgeos.dylib", "libgeos_c.dylib", "libsqlite3.dylib");
    }

    return ImmutableList.of("libz.so", "libgeos.so", "libgeos_c.so", "libsqlite3.so");
  }

  @Override
  public Map<Path, List<String>> getResources() {
    if (System.getProperty("os.name").contains("Mac")) {
      return ImmutableMap.of(
          XtraplatformNative.getLibPath(getName()), ImmutableList.of("mod_spatialite.dylib"));
    }

    return ImmutableMap.of(
        XtraplatformNative.getLibPath(getName()), ImmutableList.of("mod_spatialite.so"));
  }

  @Override
  public void preload() {
    // sqLiteLoader.load();
  }

  @Override
  public Path getExtensionPath() {
    return XtraplatformNative.getLibPath(getName()).resolve("mod_spatialite");
  }
}
