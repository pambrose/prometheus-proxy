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
                                          .help("Scrape request latency in seconds")
                                          .register();

  Gauge PROXY_PATH_MAP_SIZE = Gauge.build()
                                   .name("proxy_path_map_size")
                                   .help("Proxy path map size")
                                   .register();

  Gauge PROXY_SCRAPE_MAP_SIZE = Gauge.build()
                                     .name("proxy_scrape_map_size")
                                     .help("Proxy scrape requests map size")
                                     .register();

  Gauge AGENT_SCRAPE_QUEUE_SIZE = Gauge.build()
                                       .name("agent_scrape_queue_size")
                                       .help("Agent scrape queue size")
                                       .register();
}
