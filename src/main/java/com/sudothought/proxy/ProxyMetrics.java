package com.sudothought.proxy;

import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;

public interface ProxyMetrics {
  Gauge CONNECTED_AGENTS = Gauge.build()
                                .name("proxy_connected_agents")
                                .help("Proxy connected agents")
                                .register();

  Summary PROXY_SCRAPE_REQUESTS = Summary.build()
                                         .name("proxy_scrape_requests")
                                         .help("Proxy scrape requests")
                                         .register();

  Summary PROXY_INVALID_PATHS = Summary.build()
                                       .name("proxy_invalid_paths")
                                       .help("Proxy invalid paths")
                                       .register();

  Summary PROXY_PATHS_NOT_FOUND = Summary.build()
                                         .name("proxy_paths_not_found")
                                         .help("Proxy paths not found")
                                         .register();

  Summary PROXY_REQUESTS_TIMED_OUT = Summary.build()
                                            .name("proxy_requests_timed_out")
                                            .help("Proxy requests timed out")
                                            .register();

  Summary PROXY_SCRAPE_REQUEST_LATENCY = Summary.build()
                                                .name("proxy_scrape_request_latency_seconds")
                                                .help("Proxy scrape request latency in seconds")
                                                .register();

  Gauge PROXY_PATH_MAP_SIZE = Gauge.build()
                                   .name("proxy_path_map_size")
                                   .help("Proxy path map size")
                                   .register();

  Gauge PROXY_SCRAPE_MAP_SIZE = Gauge.build()
                                     .name("proxy_scrape_map_size")
                                     .help("Proxy scrape requests map size")
                                     .register();

  Gauge PROXY_SCRAPE_QUEUE_SIZE = Gauge.build()
                                       .name("proxy_scrape_queue_size")
                                       .help("Proxy scrape request queue size")
                                       .register();
}
