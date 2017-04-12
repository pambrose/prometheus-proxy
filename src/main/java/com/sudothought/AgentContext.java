package com.sudothought;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class AgentContext {

  private static final AtomicLong AGENT_ID_GENERATOR = new AtomicLong(0);

  private final BlockingQueue<ScrapeRequestContext> scrapeRequestQueue = new ArrayBlockingQueue<>(1000);

  private final long   agentId;
  private final String hostname;

  public AgentContext(final String hostname) {
    this.hostname = hostname;
    this.agentId = AGENT_ID_GENERATOR.incrementAndGet();
  }

  public BlockingQueue<ScrapeRequestContext> getScrapeRequestQueue() {
    return this.scrapeRequestQueue;
  }

  public long getAgentId() {
    return this.agentId;
  }

  public String getHostname() {
    return this.hostname;
  }
}
