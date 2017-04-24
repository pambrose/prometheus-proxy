package com.sudothought.common;

import static java.lang.Boolean.parseBoolean;
import static java.lang.System.getenv;

public enum EnvVars {

  // Proxy
  PROXY_CONFIG("PROXY_CONFIG"),
  PROXY_PORT("PROXY_PORT"),
  AGENT_PORT("AGENT_PORT"),

  // Agent
  AGENT_CONFIG("AGENT_CONFIG"),
  PROXY_HOSTNAME("PROXY_HOSTNAME"),
  AGENT_NAME("AGENT_NAME"),

  // Common
  METRICS_PORT("METRICS_PORT"),
  ENABLE_METRICS("ENABLE_METRICS");

  private final String text;

  EnvVars(final String text) {
    this.text = text;
  }

  public String getText() { return this.text; }

  private String getEnv() { return getenv(this.getText()); }

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
