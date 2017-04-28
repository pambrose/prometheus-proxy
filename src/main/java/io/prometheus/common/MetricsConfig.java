package io.prometheus.common;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class MetricsConfig {
  public static MetricsConfig create(final boolean enabled,
                                     final int port,
                                     final ConfigVals.Proxy2.Metrics2 metrics) {

    return new AutoValue_MetricsConfig(enabled,
                                       port,
                                       metrics.path,
                                       metrics.standardExportsEnabled,
                                       metrics.memoryPoolsExportsEnabled,
                                       metrics.garbageCollectorExportsEnabled,
                                       metrics.threadExportsEnabled,
                                       metrics.classLoadingExportsEnabled,
                                       metrics.versionInfoExportsEnabled);
  }

  public static MetricsConfig create(final boolean enabled,
                                     final int port,
                                     final ConfigVals.Agent.Metrics metrics) {

    return new AutoValue_MetricsConfig(enabled,
                                       port,
                                       metrics.path,
                                       metrics.standardExportsEnabled,
                                       metrics.memoryPoolsExportsEnabled,
                                       metrics.garbageCollectorExportsEnabled,
                                       metrics.threadExportsEnabled,
                                       metrics.classLoadingExportsEnabled,
                                       metrics.versionInfoExportsEnabled);
  }

  public abstract boolean enabled();

  public abstract int port();

  public abstract String path();

  public abstract boolean standardExportsEnabled();

  public abstract boolean memoryPoolsExportsEnabled();

  public abstract boolean garbageCollectorExportsEnabled();

  public abstract boolean threadExportsEnabled();

  public abstract boolean classLoadingExportsEnabled();

  public abstract boolean versionInfoExportsEnabled();
}


