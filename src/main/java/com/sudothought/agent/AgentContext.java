package com.sudothought.agent;

import com.sudothought.proxy.Proxy;
import com.sudothought.proxy.ScrapeRequestContext;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class AgentContext {

  private static final AtomicLong AGENT_ID_GENERATOR = new AtomicLong(0);

  private final BlockingQueue<ScrapeRequestContext> scrapeRequestQueue = new ArrayBlockingQueue<>(1024);
  private final AtomicReference<String>             hostname           = new AtomicReference<>();
  private final String                              agentId            = "" + AGENT_ID_GENERATOR.incrementAndGet();

  private final Proxy  proxy;
  private final String remoteAddr;

  public AgentContext(final Proxy proxy, final String remoteAddr) {
    this.proxy = proxy;
    this.remoteAddr = remoteAddr;
  }

  public String getAgentId() { return this.agentId; }

  public String getHostname() { return this.hostname.get(); }

  public void setHostname(String hostname) { this.hostname.set(hostname); }

  public String getRemoteAddr() { return this.remoteAddr; }

  public void addScrapeRequest(final ScrapeRequestContext scrapeRequest) {
    this.proxy.getMetrics().scrapeQueueSize.inc();
    this.scrapeRequestQueue.add(scrapeRequest);
  }

  public ScrapeRequestContext pollScrapeRequestQueue(final long waitMillis) {
    try {
      final ScrapeRequestContext retval = this.scrapeRequestQueue.poll(waitMillis, TimeUnit.MILLISECONDS);
      if (retval != null)
        this.proxy.getMetrics().scrapeQueueSize.dec();
      return retval;
    }
    catch (InterruptedException e) {
      return null;
    }
  }
}
