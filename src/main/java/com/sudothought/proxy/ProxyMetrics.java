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
                                               .register();

  public final Summary invalidPaths = Summary.build()
                                             .name("proxy_invalid_paths")
                                             .help("Proxy invalid paths")
                                             .register();

  public final Summary pathsNotFound = Summary.build()
                                              .name("proxy_paths_not_found")
                                              .help("Proxy paths not found")
                                              .register();

  public final Summary requestsTimedOut = Summary.build()
                                                 .name("proxy_requests_timed_out")
                                                 .help("Proxy requests timed out")
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
