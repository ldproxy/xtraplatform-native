package de.ii.xtraplatform.proj.domain;

import com.github.azahnen.dagger.annotations.AutoBind;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import de.ii.xtraplatform.base.domain.AppContext;
import de.ii.xtraplatform.base.domain.AppConfiguration;
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
    this.dataDirectory =
        getDataDirectory(
            appContext.getDataDir(),
            appContext.getConfiguration());
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

  private Path getDataDirectory(Path dataDir, AppConfiguration configuration) {
    String projLocation = configuration.proj.location;
    if (Paths.get(projLocation).isAbsolute()) {
      return Paths.get(projLocation);
    }

    return dataDir.resolve(projLocation);
  }
}
