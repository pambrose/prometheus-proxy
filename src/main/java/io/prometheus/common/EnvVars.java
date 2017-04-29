package io.prometheus.common;

import static java.lang.Boolean.parseBoolean;
import static java.lang.System.getenv;

public enum EnvVars {

  // Proxy
  PROXY_CONFIG,
  PROXY_PORT,
  AGENT_PORT,

  // Agent
  AGENT_CONFIG,
  PROXY_HOSTNAME,
  AGENT_NAME,

  // Common
  METRICS_ENABLED,
  METRICS_PORT,
  ADMIN_ENABLED,
  ADMIN_PORT;

  private String getEnv() { return getenv(this.name()); }

  public String getEnv(final String defaultVal) {
    return this.getEnv() != null ? this.getEnv() : defaultVal;
  }

  public boolean getEnv(final boolean defaultVal) {
    return this.getEnv() != null ? parseBoolean(this.getEnv()) : defaultVal;
  }

  public int getEnv(final int defaultVal) {
    return this.getEnv() != null ? Integer.parseInt(this.getEnv()) : defaultVal;
  }
}
