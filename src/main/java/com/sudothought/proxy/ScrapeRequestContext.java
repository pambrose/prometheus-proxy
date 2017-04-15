package com.sudothought.proxy;


import com.sudothought.grpc.ScrapeRequest;
import com.sudothought.grpc.ScrapeResponse;
import io.prometheus.client.Summary;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.sudothought.proxy.ProxyMetrics.PROXY_SCRAPE_REQUEST_LATENCY;

public class ScrapeRequestContext {

  private static final AtomicLong SCRAPE_ID_GENERATOR = new AtomicLong(0);

  private final long                            createTime        = System.currentTimeMillis();
  private final CountDownLatch                  complete          = new CountDownLatch(1);
  private final AtomicReference<ScrapeResponse> scrapeResponseRef = new AtomicReference<>();
  private final Summary.Timer                   requestTimer      = PROXY_SCRAPE_REQUEST_LATENCY.startTimer();

  private final ScrapeRequest scrapeRequest;

  public ScrapeRequestContext(final String agentId, final String path, final String accept) {
    this.scrapeRequest = ScrapeRequest.newBuilder()
                                      .setAgentId(agentId)
                                      .setScrapeId(SCRAPE_ID_GENERATOR.getAndIncrement())
                                      .setPath(path)
                                      .setAccept(accept)
                                      .build();
  }

  public long getScrapeId() {
    return this.scrapeRequest.getScrapeId();
  }

  public ScrapeRequest getScrapeRequest() { return this.scrapeRequest; }

  public boolean waitUntilComplete(final long waitMillis) {
    try {
      return this.complete.await(waitMillis, TimeUnit.MILLISECONDS);
    }
    catch (InterruptedException e) {
      // Ignore
    }
    return false;
  }

  public void markComplete() {
    this.requestTimer.observeDuration();
    this.complete.countDown();
  }

  public ScrapeResponse getScrapeResponse() { return this.scrapeResponseRef.get(); }

  public void setScrapeResponse(final ScrapeResponse scrapeResponse) { this.scrapeResponseRef.set(scrapeResponse);}

  public long ageInSecs() { return (System.currentTimeMillis() - this.createTime) / 1000;}
}
