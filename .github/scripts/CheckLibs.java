import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Verifies the native libs load on this system the way NativeLoader loads them: System.load,
 * with the libs of a bundle preloaded so their sonames resolve. Run with LD_BIND_NOW=1, otherwise
 * missing symbols only fail on first call.
 *
 * <p>Usage: java CheckLibs.java <bundle dir>...
 */
public class CheckLibs {

  public static void main(String[] bundles) throws IOException {
    System.out.println(osName() + ", " + glibcVersion());

    boolean allLoaded = true;
    for (String bundle : bundles) {
      allLoaded &= loadBundle(Path.of(bundle));
    }

    System.exit(allLoaded ? 0 : 1);
  }

  // load order inside a bundle is not known here, so retry until no more progress
  private static boolean loadBundle(Path bundle) throws IOException {
    List<Path> pending = findLibs(bundle);
    if (pending.isEmpty()) {
      System.out.println("FAIL " + bundle + ": no libs found");
      return false;
    }

    Map<Path, String> errors = new LinkedHashMap<>();
    boolean progress = true;
    while (!pending.isEmpty() && progress) {
      progress = false;
      errors.clear();
      for (Path lib : new ArrayList<>(pending)) {
        try {
          System.load(lib.toAbsolutePath().toString());
          System.out.println("OK   " + lib);
          pending.remove(lib);
          progress = true;
        } catch (UnsatisfiedLinkError e) {
          errors.put(lib, e.getMessage());
        }
      }
    }

    errors.forEach((lib, error) -> System.out.println("FAIL " + lib + ": " + error));
    return pending.isEmpty();
  }

  private static List<Path> findLibs(Path bundle) throws IOException {
    try (Stream<Path> files = Files.walk(bundle)) {
      return new ArrayList<>(
          files.filter(file -> file.getFileName().toString().contains(".so")).sorted().toList());
    }
  }

  private static String osName() throws IOException {
    return Files.readAllLines(Path.of("/etc/os-release")).stream()
        .filter(line -> line.startsWith("PRETTY_NAME="))
        .map(line -> line.substring("PRETTY_NAME=".length()).replace("\"", ""))
        .findFirst()
        .orElse("unknown os");
  }

  private static String glibcVersion() throws IOException {
    try {
      Process ldd = new ProcessBuilder("ldd", "--version").redirectErrorStream(true).start();
      return new String(ldd.getInputStream().readAllBytes()).lines().findFirst().orElse("");
    } catch (IOException e) {
      return "glibc unknown";
    }
  }
}
