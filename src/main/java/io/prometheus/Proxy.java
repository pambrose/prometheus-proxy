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

package io.prometheus;

import com.codahale.metrics.health.HealthCheck;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Maps;
import io.grpc.Attributes;
import io.prometheus.common.AdminConfig;
import io.prometheus.common.ConfigVals;
import io.prometheus.common.GenericService;
import io.prometheus.common.MetricsConfig;
import io.prometheus.common.Utils;
import io.prometheus.common.ZipkinConfig;
import io.prometheus.grpc.UnregisterPathResponse;
import io.prometheus.proxy.AgentContext;
import io.prometheus.proxy.AgentContextCleanupService;
import io.prometheus.proxy.ProxyGrpcService;
import io.prometheus.proxy.ProxyHttpService;
import io.prometheus.proxy.ProxyMetrics;
import io.prometheus.proxy.ProxyOptions;
import io.prometheus.proxy.ScrapeRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;

public class Proxy
    extends GenericService {

  private static final Logger logger = LoggerFactory.getLogger(Proxy.class);

  public static final String                 AGENT_ID        = "agent-id";
  public static final Attributes.Key<String> ATTRIB_AGENT_ID = Attributes.Key.of(AGENT_ID);

  private final Map<String, AgentContext>       agentContextMap  = Maps.newConcurrentMap(); // Map agent_id to AgentContext
  private final Map<String, AgentContext>       pathMap          = Maps.newConcurrentMap(); // Map path to AgentContext
  private final Map<Long, ScrapeRequestWrapper> scrapeRequestMap = Maps.newConcurrentMap(); // Map scrape_id to agent_id

  private final ProxyMetrics               metrics;
  private final ProxyGrpcService           grpcService;
  private final ProxyHttpService           httpService;
  private final AgentContextCleanupService agentCleanupService;

  public Proxy(final ProxyOptions options,
               final int proxyPort,
               final String inProcessServerName,
               final boolean testMode) {
    super(options.getConfigVals(),
          AdminConfig.Companion.create(options.getAdminEnabled(),
                                       options.getAdminPort(),
                                       options.getConfigVals().proxy.admin),
          MetricsConfig.Companion.create(options.getMetricsEnabled(),
                                         options.getMetricsPort(),
                                         options.getConfigVals().proxy.metrics),
          ZipkinConfig.Companion.create(options.getConfigVals().proxy.internal.zipkin),
          testMode);

    this.metrics = this.getMetricsEnabled() ? new ProxyMetrics(this) : null;
    this.grpcService = isNullOrEmpty(inProcessServerName) ? ProxyGrpcService.Companion.create(this, options.getAgentPort())
                                                          : ProxyGrpcService.Companion.create(this, inProcessServerName);
    this.httpService = new ProxyHttpService(this, proxyPort);
    this.agentCleanupService = this.getConfigVals().internal.staleAgentCheckEnabled
                               ? new AgentContextCleanupService(this) : null;

    this.addServices(this.grpcService, this.httpService, this.agentCleanupService);
    this.init();
  }

  public static void main(final String[] argv) {
    final ProxyOptions options = new ProxyOptions(argv);

    logger.info(Utils.INSTANCE.getBanner("banners/proxy.txt"));
    logger.info(Utils.INSTANCE.getVersionDesc(false));

    final Proxy proxy = new Proxy(options, options.getProxyPort(), null, false);
    proxy.startAsync();
  }

  @Override
  protected void startUp()
      throws Exception {
    super.startUp();
    this.grpcService.startAsync();
    this.httpService.startAsync();

    if (this.agentCleanupService != null)
      this.agentCleanupService.startAsync();
    else
      logger.info("Agent eviction thread not started");
  }

  @Override
  protected void shutDown()
      throws Exception {
    this.grpcService.stopAsync();
    this.httpService.stopAsync();
    if (this.agentCleanupService != null)
      this.agentCleanupService.stopAsync();
    super.shutDown();
  }

  @Override
  protected void run() {
    while (this.isRunning()) {
      Utils.INSTANCE.sleepForMillis(500);
    }
  }

  @Override
  protected void registerHealthChecks() {
    super.registerHealthChecks();
    this.getHealthCheckRegistry().register("grpc_service", this.grpcService.getHealthCheck());
    this.getHealthCheckRegistry()
        .register("scrape_response_map_check",
                  Utils.INSTANCE.mapHealthCheck(scrapeRequestMap, this.getConfigVals().internal.scrapeRequestMapUnhealthySize));
    this.getHealthCheckRegistry()
        .register("agent_scrape_request_queue",
                  new HealthCheck() {
                    @Override
                    protected Result check()
                        throws Exception {
                      final int unhealthySize = getConfigVals().internal.scrapeRequestQueueUnhealthySize;
                      final List<String> vals = getAgentContextMap().entrySet()
                                                                    .stream()
                                                                    .filter(kv -> kv.getValue().scrapeRequestQueueSize() >= unhealthySize)
                                                                    .map(kv -> format("%s %d",
                                                                                      kv.getValue(),
                                                                                      kv.getValue().scrapeRequestQueueSize()))
                                                                    .collect(Collectors.toList());
                      return vals.isEmpty() ? Result.healthy()
                                            : Result.unhealthy(format("Large scrapeRequestQueues: %s",
                                                                      Joiner.on(", ").join(vals)));
                    }
                  });

  }

  public void addAgentContext(final AgentContext agentContext) {
    this.agentContextMap.put(agentContext.getAgentId(), agentContext);
  }

  public AgentContext getAgentContext(String agentId) { return this.agentContextMap.get(agentId); }

  public AgentContext removeAgentContext(String agentId) {
    final AgentContext agentContext = this.agentContextMap.remove(agentId);
    if (agentContext != null) {
      logger.info("Removed {}", agentContext);
      agentContext.markInvalid();
    }
    else
      logger.error("Missing AgentContext for agentId: {}", agentId);

    return agentContext;
  }

  public void addToScrapeRequestMap(final ScrapeRequestWrapper scrapeRequest) {
    this.scrapeRequestMap.put(scrapeRequest.getScrapeId(), scrapeRequest);
  }

  public ScrapeRequestWrapper getFromScrapeRequestMap(long scrapeId) {
    return this.scrapeRequestMap.get(scrapeId);
  }

  public ScrapeRequestWrapper removeFromScrapeRequestMap(long scrapeId) {
    return this.scrapeRequestMap.remove(scrapeId);
  }

  public AgentContext getAgentContextByPath(final String path) { return this.pathMap.get(path); }

  public boolean containsPath(final String path) { return this.pathMap.containsKey(path);}

  public void addPath(final String path, final AgentContext agentContext) {
    synchronized (this.pathMap) {
      this.pathMap.put(path, agentContext);
      if (!this.isTestMode())
        logger.info("Added path /{} for {}", path, agentContext);
    }
  }

  public void removePath(final String path, final String agentId,
                         final UnregisterPathResponse.Builder responseBuilder) {
    synchronized (this.pathMap) {
      final AgentContext agentContext = this.pathMap.get(path);
      if (agentContext == null) {
        final String msg = format("Unable to remove path /%s - path not found", path);
        logger.info(msg);
        responseBuilder.setValid(false).setReason(msg);
      }
      else if (!agentContext.getAgentId().equals(agentId)) {
        final String msg = format("Unable to remove path /%s - invalid agentId: %s (owner is %s)",
                                  path, agentId, agentContext.getAgentId());
        logger.info(msg);
        responseBuilder.setValid(false).setReason(msg);
      }
      else {
        this.pathMap.remove(path);
        if (!this.isTestMode())
          logger.info("Removed path /{} for {}", path, agentContext);
        responseBuilder.setValid(true).setReason("");
      }
    }
  }

  public void removePathByAgentId(final String agentId) {
    synchronized (this.pathMap) {
      for (Map.Entry<String, AgentContext> elem : this.pathMap.entrySet()) {
        if (elem.getValue().getAgentId().equals(agentId)) {
          final AgentContext agentContext = this.pathMap.remove(elem.getKey());
          if (agentContext != null)
            logger.info("Removed path /{} for {}", elem.getKey(), agentContext);
          else
            logger.error("Missing path /{} for agentId: {}", elem.getKey(), agentId);
        }
      }
    }
  }

  public int pathMapSize() { return this.pathMap.size(); }

  public int getAgentContextSize() { return this.agentContextMap.size(); }

  public int getPathMapSize() { return this.pathMap.size(); }

  public int getScrapeMapSize() { return this.scrapeRequestMap.size(); }

  public ProxyMetrics getMetrics() { return this.metrics; }

  public ConfigVals.Proxy2 getConfigVals() { return this.getGenericConfigVals().proxy; }

  public Map<String, AgentContext> getAgentContextMap() { return this.agentContextMap; }

  public int getTotalAgentRequestQueueSize() {
    return this.agentContextMap.values()
                               .stream()
                               .mapToInt(AgentContext::scrapeRequestQueueSize)
                               .sum();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("proxyPort", this.httpService.getPort())
                      .add("adminService", this.getAdminEnabled() ? this.getAdminService() : "Disabled")
                      .add("metricsService", this.getMetricsEnabled() ? this.getMetricsService() : "Disabled")
                      .toString();
  }
}
