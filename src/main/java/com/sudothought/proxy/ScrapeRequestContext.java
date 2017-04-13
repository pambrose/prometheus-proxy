package com.sudothought.proxy;


import com.sudothought.grpc.ScrapeRequest;
import com.sudothought.grpc.ScrapeResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ScrapeRequestContext {

  private final long                            createTime      = System.currentTimeMillis();
  private final CountDownLatch                  complete        = new CountDownLatch(1);
  private final AtomicReference<ScrapeResponse> scrape_response = new AtomicReference<>();

  private final ScrapeRequest scrapeRequest;

  public ScrapeRequestContext(final ScrapeRequest scrapeRequest) {
    this.scrapeRequest = scrapeRequest;
  }

  public ScrapeRequest getScrapeRequest() { return this.scrapeRequest; }

  public boolean waitUntilComplete() {
    try {
      return this.complete.await(1, TimeUnit.SECONDS);
    }
    catch (InterruptedException e) {
      e.printStackTrace();
    }
    return false;
  }

  public void markComplete() { this.complete.countDown(); }

  public AtomicReference<ScrapeResponse> getScrapeResponse() { return this.scrape_response; }

  public long ageInSecs() { return (System.currentTimeMillis() - this.createTime) / 1000;}
}
