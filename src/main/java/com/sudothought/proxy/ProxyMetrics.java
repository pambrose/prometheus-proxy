package com.sudothought.proxy;

import com.sudothought.common.SamplerGauge;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;

public class ProxyMetrics {

  public final Gauge startTime = Gauge.build()
                                      .name("proxy_start_time_seconds")
                                      .help("Proxy start time in seconds")
                                      .register();

  public final Counter scrapeRequests = Counter.build()
                                               .name("proxy_scrape_requests")
                                               .help("Proxy scrape requests")
                                               .labelNames("type")
                                               .register();

  public final Summary scrapeRequestLatency = Summary.build()
                                                     .name("proxy_scrape_request_latency_seconds")
                                                     .help("Proxy scrape request latency in seconds")
                                                     .register();

  public final Gauge agentMapSize = Gauge.build()
                                         .name("proxy_agent_map_size")
                                         .help("Proxy connected agents")
                                         .register();

  public final Gauge pathMapSize = Gauge.build()
                                        .name("proxy_path_map_size")
                                        .help("Proxy path map size")
                                        .register();

  public final Gauge scrapeMapSize = Gauge.build()
                                          .name("proxy_scrape_map_size")
                                          .help("Proxy scrape requests map size")
                                          .register();

  public final Gauge cummulativeAgentRequestQueueSize = Gauge.build()
                                                             .name("proxy_cummulative_agent_queue_size")
                                                             .help("Proxy cummulative agent queue size")
                                                             .register();

  public final SamplerGauge samplerAgentMapSize;
  public final SamplerGauge samplerPathMapSize;
  public final SamplerGauge samplerScrapeMapSize;
  public final SamplerGauge samplercummulativeAgentRequestQueueSize;

  public ProxyMetrics(Proxy proxy) {

    this.samplerAgentMapSize = new SamplerGauge("test_proxy_agent_map_size",
                                                "Proxy connected agents",
                                                proxy::getAgentContextSize);

    this.samplerPathMapSize = new SamplerGauge("test_proxy_path_map_size",
                                               "Proxy path map size",
                                               proxy::getPathMapSize);

    this.samplerScrapeMapSize = new SamplerGauge("test_proxy_scrape_map_size",
                                                 "Proxy scrape map size",
                                                 proxy::getScrapeMapSize);

    this.samplercummulativeAgentRequestQueueSize = new SamplerGauge("test_proxy_cummulative_agent_queue_size",
                                                                    "Proxy cummulative agent queue size",
                                                                    proxy::getTotalAgentRequestQueueSize);


  }

  public void register() {
    this.samplerAgentMapSize.register();
    this.samplerPathMapSize.register();
    this.samplerScrapeMapSize.register();
    this.samplercummulativeAgentRequestQueueSize.register();
  }

}
