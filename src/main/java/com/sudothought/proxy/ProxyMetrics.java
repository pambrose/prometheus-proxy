package com.sudothought.proxy;

import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;

public interface ProxyMetrics {
  Gauge   CONNECTED_AGENTS       = Gauge.build()
                                        .name("connected_agents")
                                        .help("Connected agents")
                                        .register();
  Summary SCRAPE_REQUESTS        = Summary.build()
                                          .name("scrape_requests")
                                          .help("Scrape requests")
                                          .register();
  Summary SCRAPE_REQUEST_LATENCY = Summary.build()
                                          .name("scrape_request_latency_seconds")
                                          .help("Request request latency in seconds")
                                          .register();
}
