package com.sudothought.proxy;

import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;

public class ProxyMetrics {
  public final Gauge agentMapSize = Gauge.build()
                                         .name("proxy_connected_agents")
                                         .help("Proxy connected agents")
                                         .register();

  public final Summary scrapeRequests = Summary.build()
                                               .name("proxy_scrape_requests")
                                               .help("Proxy scrape requests")
                                               .labelNames("type")
                                               .register();

  public final Summary scrapeRequestLatency = Summary.build()
                                                     .name("proxy_scrape_request_latency_seconds")
                                                     .help("Proxy scrape request latency in seconds")
                                                     .register();

  public final Gauge pathMapSize = Gauge.build()
                                        .name("proxy_path_map_size")
                                        .help("Proxy path map size")
                                        .register();

  public final Gauge scrapeMapSize = Gauge.build()
                                          .name("proxy_scrape_map_size")
                                          .help("Proxy scrape requests map size")
                                          .register();

  public final Gauge scrapeQueueSize = Gauge.build()
                                            .name("proxy_scrape_queue_size")
                                            .help("Proxy scrape request queue size")
                                            .register();
}
