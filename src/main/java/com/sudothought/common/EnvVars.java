package com.sudothought.common;

import static java.lang.Boolean.parseBoolean;
import static java.lang.System.getenv;

public enum EnvVars {

  PROXY_CONFIG("PROXY_CONFIG"),
  AGENT_CONFIG("AGENT_CONFIG"),
  AGENT_NAME("AGENT_NAME"),
  METRICS_PORT("METRICS_PORT"),
  DISABLE_METRICS("DISABLE_METRICS");

  private final String constVal;

  EnvVars(final String constVal) {
    this.constVal = constVal;
  }

  public String getConstVal() { return this.constVal; }

  public String getEnv(final String defaultVal) {
    return getenv(this.constVal) != null ? this.getConstVal() : defaultVal;
  }

  public boolean getEnv(final boolean defaultVal) {
    return getenv(this.constVal) != null ? parseBoolean(this.getConstVal()) : defaultVal;
  }

  public int getEnv(final int defaultVal) {
    return getenv(this.constVal) != null ? Integer.parseInt(this.getConstVal()) : defaultVal;
  }
}
