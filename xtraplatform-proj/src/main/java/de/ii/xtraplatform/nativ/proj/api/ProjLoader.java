package de.ii.xtraplatform.nativ.proj.api;

import de.ii.xtraplatform.nativ.NativeLoader;
import java.nio.file.Path;

public interface ProjLoader extends NativeLoader {

  Path getDataDirectory();
}
