package com.sudothought.common;

import io.prometheus.client.hotspot.ClassLoadingExports;
import io.prometheus.client.hotspot.GarbageCollectorExports;
import io.prometheus.client.hotspot.MemoryPoolsExports;
import io.prometheus.client.hotspot.StandardExports;
import io.prometheus.client.hotspot.ThreadExports;
import io.prometheus.client.hotspot.VersionInfoExports;

public class SystemMetrics {
  private static boolean initialized = false;

  public static synchronized void initialize(final boolean enableStandardExports,
                                             final boolean enableMemoryPoolsExports,
                                             final boolean enableGarbageCollectorExports,
                                             final boolean enableThreadExports,
                                             final boolean enableClassLoadingExports,
                                             final boolean enableVersionInfoExports) {
    if (!initialized) {
      if (enableStandardExports)
        new StandardExports().register();
      if (enableMemoryPoolsExports)
        new MemoryPoolsExports().register();
      if (enableGarbageCollectorExports)
        new GarbageCollectorExports().register();
      if (enableThreadExports)
        new ThreadExports().register();
      if (enableClassLoadingExports)
        new ClassLoadingExports().register();
      if (enableVersionInfoExports)
        new VersionInfoExports().register();
      initialized = true;
    }
  }

}
