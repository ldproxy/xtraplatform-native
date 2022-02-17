package de.ii.xtraplatform.nativ.proj.api;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import de.ii.xtraplatform.dropwizard.domain.XtraPlatform;
import de.ii.xtraplatform.nativ.sqlite.api.SQLiteLoader;
import de.ii.xtraplatform.nativ.sqlite.api.SQLiteLoaderImpl;
import de.ii.xtraplatform.runtime.domain.Constants;
import de.ii.xtraplatform.runtime.domain.XtraPlatformConfiguration;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Context;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.osgi.framework.BundleContext;

@Component
@Provides
@Instantiate
public class ProjLoaderImpl implements ProjLoader {

  private final Path dataDirectory;
  public ProjLoaderImpl(@Context BundleContext bundleContext, @Requires XtraPlatform xtraPlatform) {
    this.dataDirectory =
        getDataDirectory(
            bundleContext.getProperty(Constants.DATA_DIR_KEY),
            xtraPlatform.getConfiguration());
  }

  // for unit tests only
  ProjLoaderImpl(Path dataDirectory) {
    this.dataDirectory = dataDirectory;
  }

  @Override
  public String getName() {
    return "proj-8.2.0-1";
  }

  @Override
  public String getLabel() {
    return "PROJ";
  }

  @Override
  public Map<String, List<String>> getLibraries() {
    return ImmutableMap.of("linux-x86_64", ImmutableList.of("libsqlite3.so", "libz.so", "libtiff.so", "libproj.so"));
  }

  @Override
  public Map<Path, List<String>> getResources() {
    return ImmutableMap.of(dataDirectory, ImmutableList.of(
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
        "world"
    ));
  }

  @Override
  public void preload() {
    //sqLiteLoader.load();
  }

  @Override
  public Path getDataDirectory() {
    return dataDirectory;
  }

  private Path getDataDirectory(String dataDir, XtraPlatformConfiguration configuration) {
    String projLocation = configuration.proj.location;
    if (Paths.get(projLocation).isAbsolute()) {
      return Paths.get(projLocation);
    }

    return Paths.get(dataDir, projLocation);
  }
}
