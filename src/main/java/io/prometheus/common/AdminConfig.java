package io.prometheus.common;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class AdminConfig {
  public static AdminConfig create(final ConfigVals.Proxy2.Admin2 admin) {

    return new AutoValue_AdminConfig(admin.enabled,
                                     admin.port,
                                     admin.pingPath,
                                     admin.healthCheckPath,
                                     admin.theadtDumpPath);
  }

  public static AdminConfig create(final ConfigVals.Agent.Admin admin) {

    return new AutoValue_AdminConfig(admin.enabled,
                                     admin.port,
                                     admin.pingPath,
                                     admin.healthCheckPath,
                                     admin.theadtDumpPath);
  }

  public abstract boolean enabled();

  public abstract int port();

  public abstract String pingPath();

  public abstract String healthCheckPath();

  public abstract String theadtDumpPath();

}


