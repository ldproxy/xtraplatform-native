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

  public static boolean copyLibsToTmpDir(
      Class<?> contextClass, Map<String, List<String>> libs, String parentName,
      String parentLabel) {
    String osIdentifier = OSInfo.getIdentifierForCurrentOS();

    if (!libs.containsKey(osIdentifier)) {
      LOGGER.warn(
          "{} is not supported for OS '{}-{}'. It might work if you install {} as a system library.",
          parentLabel,
          System.getProperty("os.name"),
          System.getProperty("os.arch"),
          parentName);
      return true;
    }

    libs.get(osIdentifier)
        .forEach(lib -> copyLibToTmpDir(contextClass, parentName, osIdentifier, lib));

    return false;
  }

  public static void loadLibs(List<String> libs, String parentName) {
    libs.forEach(lib -> loadLib(parentName, lib));
  }

  public static void copyResources(Class<?> contextClass, Map<Path, List<String>> resources) {
    resources.forEach(
        (path, files) -> {
          files.forEach(resource -> {
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

  private static void copyLibToTmpDir(Class<?> contextClass, String parentName,
      String osIdentifier, String resource) {
    File lib = TMP_DIR.resolve(parentName).resolve(osIdentifier).resolve(resource).toFile();
    if (!lib.exists()) {
      try {
        Files.createDirectories(lib.getParentFile().toPath());
        Resources.copy(Resources.getResource(contextClass,
            String.format("/%s/%s", osIdentifier, resource)), new FileOutputStream(lib));
      } catch (Throwable e) {
        throw new IllegalStateException("Could not create file: " + lib.toString());
      }
    }
  }

  private static void loadLib(String parentName, String libName) {
    Path lib = TMP_DIR.resolve(parentName).resolve(OSInfo.getIdentifierForCurrentOS())
        .resolve(libName);
    try {
      System.load(lib.toString());
    } catch (Throwable e) {
      throw new IllegalStateException("Could not load library: " + lib.toString());
    }
  }
}
