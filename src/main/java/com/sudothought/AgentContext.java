package com.sudothought;

import java.util.concurrent.atomic.AtomicLong;

public class AgentContext {

  private static final AtomicLong ID_GENERATOR = new AtomicLong(0);

  private final long   agentId;
  private final String hostname;

  public AgentContext(final String hostname) {
    this.hostname = hostname;
    this.agentId = ID_GENERATOR.incrementAndGet();
  }

  public long getAgentId() {
    return this.agentId;
  }

  public String getHostname() {
    return this.hostname;
  }
}
