package com.sudothought.proxy;


import brave.Span;
import com.google.common.base.MoreObjects;
import com.sudothought.grpc.ScrapeRequest;
import com.sudothought.grpc.ScrapeResponse;
import io.prometheus.client.Summary;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class ScrapeRequestWrapper {

  private static final AtomicLong SCRAPE_ID_GENERATOR = new AtomicLong(0);

  private final long                            createTime        = System.currentTimeMillis();
  private final CountDownLatch                  complete          = new CountDownLatch(1);
  private final AtomicReference<ScrapeResponse> scrapeResponseRef = new AtomicReference<>();

  private final Span          rootSpan;
  private final HttpServer    httpServer;
  private final Summary.Timer requestTimer;
  private final ScrapeRequest scrapeRequest;

  public ScrapeRequestWrapper(final HttpServer httpServer,
                              final Span rootSpan,
                              final String agentId,
                              final String path,
                              final String accept) {
    this.httpServer = httpServer;
    this.rootSpan = rootSpan;
    this.requestTimer = this.httpServer.getProxy().getMetrics().scrapeRequestLatency.startTimer();
    this.scrapeRequest = ScrapeRequest.newBuilder()
                                      .setAgentId(agentId)
                                      .setScrapeId(SCRAPE_ID_GENERATOR.getAndIncrement())
                                      .setPath(path)
                                      .setAccept(accept)
                                      .build();
  }

  public HttpServer getHttpServer() { return this.httpServer; }

  public Span getRootSpan() { return this.rootSpan; }

  public long getScrapeId() { return this.scrapeRequest.getScrapeId(); }

  public ScrapeRequest getScrapeRequest() { return this.scrapeRequest; }

  public ScrapeResponse getScrapeResponse() { return this.scrapeResponseRef.get(); }

  public void setScrapeResponse(final ScrapeResponse scrapeResponse) { this.scrapeResponseRef.set(scrapeResponse);}

  public long ageInSecs() { return (System.currentTimeMillis() - this.createTime) / 1000;}

  public void markComplete() {
    this.requestTimer.observeDuration();
    this.complete.countDown();
  }

  public boolean waitUntilComplete(final long waitMillis) {
    try {
      return this.complete.await(waitMillis, TimeUnit.MILLISECONDS);
    }
    catch (InterruptedException e) {
      // Ignore
    }
    return false;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("scrapeId", scrapeRequest.getScrapeId())
                      .add("path", scrapeRequest.getPath())
                      .toString();
  }
}
