/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus.proxy;

import io.prometheus.Proxy;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;
import io.prometheus.common.SamplerGauge;

public class ProxyMetrics {

  public final Counter scrapeRequests = Counter.build()
                                               .name("proxy_scrape_requests")
                                               .help("Proxy scrape requests")
                                               .labelNames("type")
                                               .register();

  public final Counter connects = Counter.build()
                                         .name("proxy_connect_count")
                                         .help("Proxy connect count")
                                         .register();

  public final Counter agentEvictions = Counter.build()
                                               .name("proxy_eviction_count")
                                               .help("Proxy eviction count")
                                               .register();

  public final Counter heartbeats = Counter.build()
                                           .name("proxy_heartbeat_count")
                                           .help("Proxy heartbeat count")
                                           .register();

  public final Summary scrapeRequestLatency = Summary.build()
                                                     .name("proxy_scrape_request_latency_seconds")
                                                     .help("Proxy scrape request latency in seconds")
                                                     .register();

  public ProxyMetrics(Proxy proxy) {

    Gauge.build()
         .name("proxy_start_time_seconds")
         .help("Proxy start time in seconds")
         .register()
         .setToCurrentTime();

    new SamplerGauge("proxy_agent_map_size",
                     "Proxy connected agents",
                     proxy::getAgentContextSize).register();

    new SamplerGauge("proxy_path_map_size",
                     "Proxy path map size",
                     proxy::getPathMapSize).register();

    new SamplerGauge("proxy_scrape_map_size",
                     "Proxy scrape map size",
                     proxy::getScrapeMapSize).register();

    new SamplerGauge("proxy_cummulative_agent_queue_size",
                     "Proxy cummulative agent queue size",
                     proxy::getTotalAgentRequestQueueSize).register();
  }
}
