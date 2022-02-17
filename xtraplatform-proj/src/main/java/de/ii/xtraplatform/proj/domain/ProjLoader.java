package de.ii.xtraplatform.proj.domain;

import de.ii.xtraplatform.nativ.NativeLoader;
import java.nio.file.Path;

public interface ProjLoader extends NativeLoader {

  Path getDataDirectory();
}
