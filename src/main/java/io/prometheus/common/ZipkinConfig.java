package io.prometheus.common;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class ZipkinConfig {
  public static ZipkinConfig create(final ConfigVals.Proxy2.Internal2.Zipkin2 zipkin) {
    return new AutoValue_ZipkinConfig(zipkin.enabled,
                                      zipkin.hostname,
                                      zipkin.port,
                                      zipkin.path,
                                      zipkin.serviceName);
  }

  public static ZipkinConfig create(final ConfigVals.Agent.Internal.Zipkin zipkin) {
    return new AutoValue_ZipkinConfig(zipkin.enabled,
                                      zipkin.hostname,
                                      zipkin.port,
                                      zipkin.path,
                                      zipkin.serviceName);
  }

  public abstract boolean enabled();

  public abstract String hostname();

  public abstract int port();

  public abstract String path();

  public abstract String serviceName();
}


