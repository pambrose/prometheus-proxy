package com.sudothought.agent;

import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;

public interface AgentMetrics {
  Summary AGENT_SCRAPE_REQUESTS = Summary.build()
                                         .name("agent_scrape_requests")
                                         .help("Agent scrape requests")
                                         .register();

  Summary AGENT_SCRAPE_REQUEST_LATENCY = Summary.build()
                                                .name("agent_scrape_request_latency_seconds")
                                                .help("Agent scrape request latency in seconds")
                                                .register();

  Gauge AGENT_SCRAPE_QUEUE_SIZE = Gauge.build()
                                       .name("agent_scrape_queue_size")
                                       .help("Agent scrape response queue size")
                                       .register();
}
