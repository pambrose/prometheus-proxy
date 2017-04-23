package com.sudothought.proxy;

import com.google.common.base.MoreObjects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.String.format;

public class AgentContext {

  private static final Logger     logger             = LoggerFactory.getLogger(AgentContext.class);
  private static final AtomicLong AGENT_ID_GENERATOR = new AtomicLong(0);

  private final String                  agentId          = format("%s", AGENT_ID_GENERATOR.incrementAndGet());
  private final AtomicBoolean           valid            = new AtomicBoolean(true);
  private final AtomicLong              lastActivityTime = new AtomicLong();
  private final AtomicReference<String> agentName        = new AtomicReference<>();
  private final AtomicReference<String> hostname         = new AtomicReference<>();

  private final String                              remoteAddr;
  private final BlockingQueue<ScrapeRequestWrapper> scrapeRequestQueue;
  private final long                                waitMillis;

  public AgentContext(final Proxy proxy, final String remoteAddr) {
    this.remoteAddr = remoteAddr;
    final int queueSize = proxy.getConfigVals().internal.scrapeRequestQueueSize;
    this.scrapeRequestQueue = new ArrayBlockingQueue<>(queueSize);
    this.waitMillis = proxy.getConfigVals().internal.scrapeRequestQueueCheckMillis;

    this.markActivity();
  }

  public String getAgentId() { return this.agentId; }

  public String getHostname() { return this.hostname.get(); }

  public void setHostname(String hostname) { this.hostname.set(hostname); }

  public String getAgentName() { return this.agentName.get(); }

  public void setAgentName(String agentName) { this.agentName.set(agentName); }

  public String getRemoteAddr() { return this.remoteAddr; }

  public void addToScrapeRequestQueue(final ScrapeRequestWrapper scrapeRequest) {
    this.scrapeRequestQueue.add(scrapeRequest);
  }

  public int scrapeRequestQueueSize() { return this.scrapeRequestQueue.size(); }

  public ScrapeRequestWrapper pollScrapeRequestQueue() {
    try {
      return this.scrapeRequestQueue.poll(waitMillis, TimeUnit.MILLISECONDS);
    }
    catch (InterruptedException e) {
      logger.warn("Thread interrupted", e);
      return null;
    }
  }

  public long inactivitySecs() { return (System.currentTimeMillis() - this.lastActivityTime.get()) / 1000;}

  public boolean isValid() { return this.valid.get();}

  public void markInvalid() { this.valid.set(false);}

  public void markActivity() { this.lastActivityTime.set(System.currentTimeMillis()); }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("agentId", this.agentId)
                      .add("valid", this.isValid())
                      .add("agentName", this.agentName)
                      .add("hostname", this.hostname)
                      .add("remoteAddr", this.remoteAddr)
                      .add("inactivitySecs", this.inactivitySecs())
                      .toString();
  }
}
