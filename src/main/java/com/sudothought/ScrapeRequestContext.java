package com.sudothought;

import com.cinch.grpc.ScrapeRequest;
import com.sudothought.utils.BooleanMonitor;

import java.util.concurrent.atomic.AtomicReference;

public class ScrapeRequestContext {

  private final BooleanMonitor          complete        = new BooleanMonitor(false);
  private final AtomicReference<String> scrape_response = new AtomicReference<>();

  private final ScrapeRequest scrapeRequest;

  public ScrapeRequestContext(final ScrapeRequest scrapeRequest) {
    this.scrapeRequest = scrapeRequest;
  }

  public ScrapeRequest getScrapeRequest() {
    return this.scrapeRequest;
  }

  public BooleanMonitor getComplete() {
    return this.complete;
  }

  public String getScrapeResponse() {
    return this.scrape_response.get();
  }
}
