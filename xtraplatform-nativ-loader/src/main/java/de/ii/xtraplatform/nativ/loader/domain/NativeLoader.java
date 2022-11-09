/*
 * Copyright 2022 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.nativ.loader.domain;

import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface NativeLoader {

  default void load() {
    XtraplatformNative.copyLibsToTmpDir(this.getClass(), getLibraries(), getName(), getLabel());
    preload();
    XtraplatformNative.loadLibs(getLibraries(), getName());
    XtraplatformNative.copyResources(this.getClass(), getName(), getResources());
  }

  default void preload() {}

  String getName();

  String getLabel();

  List<String> getLibraries();

  default Map<Path, List<String>> getResources() {
    return ImmutableMap.of();
  }
}
