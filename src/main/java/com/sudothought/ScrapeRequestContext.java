package com.sudothought;


import com.sudothought.grpc.ScrapeRequest;
import com.sudothought.grpc.ScrapeResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class ScrapeRequestContext {

  private final CountDownLatch                  complete        = new CountDownLatch(1);
  private final AtomicReference<ScrapeResponse> scrape_response = new AtomicReference<>();

  private final ScrapeRequest scrapeRequest;

  public ScrapeRequestContext(final ScrapeRequest scrapeRequest) {
    this.scrapeRequest = scrapeRequest;
  }

  public ScrapeRequest getScrapeRequest() { return this.scrapeRequest; }

  public void waitUntilComplete() {
    try {
      this.complete.await();
    }
    catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public void markComplete() { this.complete.countDown(); }

  public AtomicReference<ScrapeResponse> getScrapeResponse() { return this.scrape_response; }
}
