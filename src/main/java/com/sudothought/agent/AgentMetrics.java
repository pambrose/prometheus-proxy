package com.sudothought.agent;

import com.sudothought.common.SamplerGauge;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;

public class AgentMetrics {

  public final Counter scrapeRequests = Counter.build()
                                               .name("agent_scrape_requests")
                                               .help("Agent scrape requests")
                                               .labelNames("type")
                                               .register();

  public final Counter connects = Counter.build()
                                         .name("agent_connect_count")
                                         .help("Agent connect counts")
                                         .labelNames("type")
                                         .register();

  public final Summary scrapeRequestLatency = Summary.build()
                                                     .name("agent_scrape_request_latency_seconds")
                                                     .help("Agent scrape request latency in seconds")
                                                     .labelNames("agent_name")
                                                     .register();

  public AgentMetrics(final Agent agent) {
    Gauge.build()
         .name("agent_start_time_seconds")
         .help("Agent start time in seconds")
         .register()
         .setToCurrentTime();

    new SamplerGauge("agent_scrape_queue_size",
                     "Agent scrape response queue size",
                     agent::getScrapeResponseQueueSize).register();
  }
}
