/*
 * Copyright 2022 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.xtralink.app;

import de.ii.xtraplatform.nativ.loader.domain.NativeLoader;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class XtralinkLoaderImpl implements NativeLoader {

  @Inject
  public XtralinkLoaderImpl() {}

  @Override
  public String getName() {
    return "xtralink-0.9.4-0";
  }

  @Override
  public String getLabel() {
    return "xtralink";
  }

  @Override
  public List<String> getLibraries() {
    if (System.getProperty("os.name").contains("Mac")) {
      return List.of("libxtralink.dylib");
    }

    return List.of("libxtralink.so");
  }

  @Override
  public void preload(Map<String, Path> paths) {
    System.setProperty("genffi.library.xtralink", paths.get(getLibraries().get(0)).toString());
  }
}
