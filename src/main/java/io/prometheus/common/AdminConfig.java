package io.prometheus.common;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class AdminConfig {
  public static AdminConfig create(final boolean enabled,
                                   final int port,
                                   final ConfigVals.Proxy2.Admin2 admin) {

    return new AutoValue_AdminConfig(enabled,
                                     port,
                                     admin.pingPath,
                                     admin.versionPath,
                                     admin.healthCheckPath,
                                     admin.threadDumpPath);
  }

  public static AdminConfig create(final boolean enabled,
                                   final int port,
                                   final ConfigVals.Agent.Admin admin) {

    return new AutoValue_AdminConfig(enabled,
                                     port,
                                     admin.pingPath,
                                     admin.versionPath,
                                     admin.healthCheckPath,
                                     admin.threadDumpPath);
  }

  public abstract boolean enabled();

  public abstract int port();

  public abstract String pingPath();

  public abstract String versionPath();

  public abstract String healthCheckPath();

  public abstract String threadDumpPath();
}


