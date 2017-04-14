package com.sudothought.agent;

import com.sudothought.proxy.ScrapeRequestContext;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class AgentContext {

  private static final AtomicLong AGENT_ID_GENERATOR = new AtomicLong(0);

  private final BlockingQueue<ScrapeRequestContext> scrapeRequestQueue = new ArrayBlockingQueue<>(1024);
  private final AtomicReference<String>             hostname           = new AtomicReference<>();
  private final String                              agentId            = "" + AGENT_ID_GENERATOR.incrementAndGet();

  private final String remoteAddr;

  public AgentContext(final String remoteAddr) {
    this.remoteAddr = remoteAddr;
  }

  public BlockingQueue<ScrapeRequestContext> getScrapeRequestQueue() { return this.scrapeRequestQueue; }

  public String getAgentId() { return this.agentId; }

  public String getHostname() { return this.hostname.get(); }

  public void setHostname(String hostname) { this.hostname.set(hostname); }

  public String getRemoteAddr() { return this.remoteAddr; }
}
