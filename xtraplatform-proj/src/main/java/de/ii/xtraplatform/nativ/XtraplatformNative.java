/*
 * Copyright 2022 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.nativ;

import static de.ii.xtraplatform.base.domain.Constants.TMP_DIR_PROP;

import com.google.common.io.Resources;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XtraplatformNative {

  private static final Logger LOGGER = LoggerFactory.getLogger(XtraplatformNative.class);
  private static final Path TMP_DIR = Paths.get(System.getProperty(TMP_DIR_PROP));
  public static final String LIB_DIR_NAME = "lib";

  public static boolean copyLibsToTmpDir(
      Class<?> contextClass, List<String> libs, String parentName, String parentLabel) {

    libs.forEach(lib -> copyLibToTmpDir(contextClass, parentName, lib));

    return false;
  }

  public static void loadLibs(List<String> libs, String parentName) {
    libs.forEach(lib -> loadLib(parentName, lib));
  }

  public static void copyResources(Class<?> contextClass, Map<Path, List<String>> resources) {
    resources.forEach(
        (path, files) -> {
          files.forEach(
              resource -> {
                File file = path.resolve(resource).toFile();
                if (!file.exists()) {
                  try {
                    Files.createDirectories(path);
                    Resources.copy(
                        Resources.getResource(contextClass, String.format("/data/%s", resource)),
                        new FileOutputStream(file));
                  } catch (IOException e) {
                    throw new IllegalStateException("Could not create file: " + file.toString());
                  }
                }
              });
        });
  }

  private static void copyLibToTmpDir(Class<?> contextClass, String parentName, String resource) {
    File lib = TMP_DIR.resolve(parentName).resolve(LIB_DIR_NAME).resolve(resource).toFile();
    if (!lib.exists()) {
      try {
        Files.createDirectories(lib.getParentFile().toPath());
        Resources.copy(
            Resources.getResource(contextClass, String.format("/%s/%s", LIB_DIR_NAME, resource)),
            new FileOutputStream(lib));
      } catch (Throwable e) {
        throw new IllegalStateException("Could not create file: " + lib.toString());
      }
    }
  }

  private static void loadLib(String parentName, String libName) {
    Path lib = TMP_DIR.resolve(parentName).resolve(LIB_DIR_NAME).resolve(libName);
    try {
      System.load(lib.toString());
    } catch (Throwable e) {
      throw new IllegalStateException("Could not load library: " + lib.toString());
    }
  }
}
