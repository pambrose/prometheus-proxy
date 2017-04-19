package com.sudothought.proxy;

import com.google.common.base.MoreObjects;
import com.sudothought.common.InstrumentedBlockingQueue;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class AgentContext {

  private static final AtomicLong AGENT_ID_GENERATOR = new AtomicLong(0);

  private final AtomicReference<String> hostname = new AtomicReference<>();
  private final String                  agentId  = "" + AGENT_ID_GENERATOR.incrementAndGet();

  private final String                              remoteAddr;
  private final BlockingQueue<ScrapeRequestWrapper> scrapeRequestQueue;

  public AgentContext(final Proxy proxy, final String remoteAddr) {
    this.remoteAddr = remoteAddr;
    final int queueSize = proxy.getConfigVals().internal.scrapeQueueSize;
    this.scrapeRequestQueue = proxy.isMetricsEnabled()
                              ? new InstrumentedBlockingQueue<>(new ArrayBlockingQueue<>(queueSize),
                                                                proxy.getMetrics().scrapeQueueSize)
                              : new ArrayBlockingQueue<>(queueSize);
  }

  public String getAgentId() { return this.agentId; }

  public String getHostname() { return this.hostname.get(); }

  public void setHostname(String hostname) { this.hostname.set(hostname); }

  public String getRemoteAddr() { return this.remoteAddr; }

  public void addToScrapeRequestQueue(final ScrapeRequestWrapper scrapeRequest) {
    this.scrapeRequestQueue.add(scrapeRequest);
  }

  public int scrapeRequestQueueSize() { return this.scrapeRequestQueue.size(); }

  public ScrapeRequestWrapper pollScrapeRequestQueue(final long waitMillis) {
    try {
      return this.scrapeRequestQueue.poll(waitMillis, TimeUnit.MILLISECONDS);
    }
    catch (InterruptedException e) {
      return null;
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("agentId", agentId)
                      .add("hostname", hostname)
                      .add("remoteAddr", remoteAddr)
                      .toString();
  }
}
