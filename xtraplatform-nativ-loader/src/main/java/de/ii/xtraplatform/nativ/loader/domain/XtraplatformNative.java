/*
 * Copyright 2022 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.nativ.loader.domain;

import static de.ii.xtraplatform.base.domain.Constants.TMP_DIR_PROP;

import com.google.common.io.Resources;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@SuppressWarnings("PMD.AvoidCatchingGenericException")
public final class XtraplatformNative {

  private static final Path TMP_DIR = Paths.get(System.getProperty(TMP_DIR_PROP));
  public static final String LIB_DIR_NAME = "lib";
  public static final String DATA_DIR_NAME = "data";

  private XtraplatformNative() {}

  public static void copyLibsToTmpDir(
      Class<?> contextClass, List<String> libs, String parentName, String parentLabel) {

    libs.forEach(lib -> copyLibToTmpDir(contextClass, parentName, lib));
  }

  public static void loadLibs(List<String> libs, String parentName) {
    libs.forEach(lib -> loadLib(parentName, lib));
  }

  public static void copyResources(
      Class<?> contextClass, String parentName, Map<Path, List<String>> resources) {
    resources.forEach(
        (path, files) -> {
          files.forEach(
              resource -> {
                File file = path.resolve(resource).toFile();
                if (!file.exists()) {
                  try {
                    Files.createDirectories(path);
                    Resources.copy(
                        Resources.getResource(
                            contextClass,
                            String.format("/%s/%s/%s", parentName, DATA_DIR_NAME, resource)),
                        Files.newOutputStream(file.toPath()));
                  } catch (IOException e) {
                    throw new IllegalStateException("Could not create file: " + file, e);
                  }
                }
              });
        });
  }

  public static Path getLibPath(String parentName) {
    return TMP_DIR.resolve(parentName).resolve(LIB_DIR_NAME);
  }

  public static Path getDataPath(String parentName) {
    return TMP_DIR.resolve(parentName).resolve(DATA_DIR_NAME);
  }

  private static void copyLibToTmpDir(Class<?> contextClass, String parentName, String resource) {
    File lib = getLibPath(parentName).resolve(resource).toFile();
    if (!lib.exists()) {
      try {
        Files.createDirectories(lib.getParentFile().toPath());
        Resources.copy(
            Resources.getResource(
                contextClass, String.format("/%s/%s/%s", parentName, LIB_DIR_NAME, resource)),
            Files.newOutputStream(lib.toPath()));
      } catch (Throwable e) {
        throw new IllegalStateException("Could not create file: " + lib, e);
      }
    }
  }

  private static void loadLib(String parentName, String libName) {
    Path lib = getLibPath(parentName).resolve(libName);
    try {
      System.load(lib.toString());
    } catch (Throwable e) {
      throw new IllegalStateException("Could not load library: " + lib, e);
    }
  }
}
