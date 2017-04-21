package com.sudothought.proxy;

import com.github.kristofa.brave.Brave;
import com.google.common.collect.Maps;
import com.sudothought.common.ConfigVals;
import com.sudothought.common.MetricsServer;
import com.sudothought.common.SystemMetrics;
import com.sudothought.common.Utils;
import com.sudothought.common.ZipkinReporter;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.sudothought.common.EnvVars.PROXY_CONFIG;

public class Proxy {

  private static final Logger logger = LoggerFactory.getLogger(Proxy.class);

  private final AtomicBoolean                   stopped          = new AtomicBoolean(false);
  private final Map<String, AgentContext>       agentContextMap  = Maps.newConcurrentMap(); // Map agent_id to AgentContext
  private final Map<String, AgentContext>       pathMap          = Maps.newConcurrentMap(); // Map path to AgentContext
  private final Map<Long, ScrapeRequestWrapper> scrapeRequestMap = Maps.newConcurrentMap(); // Map scrape_id to agent_id
  private final ExecutorService                 cleanupService   = Executors.newFixedThreadPool(1);

  private final ConfigVals      configVals;
  private final MetricsServer   metricsServer;
  private final ProxyMetrics    metrics;
  private final ZipkinReporter  zipkinReporter;
  private final ProxyGrpcServer grpcServer;
  private final ProxyHttpServer httpServer;

  public Proxy(final ConfigVals configVals,
               final int grpcPort,
               final int httpPort,
               final boolean metricsEnabled,
               final int metricsPort,
               final String inProcessServerName)
      throws IOException {
    this.configVals = configVals;

    if (metricsEnabled) {
      logger.info("Metrics server enabled");
      this.metricsServer = new MetricsServer(metricsPort, this.getConfigVals().metrics.path);
      this.metrics = new ProxyMetrics(this);
      SystemMetrics.initialize(this.getConfigVals().metrics.standardExportsEnabled,
                               this.getConfigVals().metrics.memoryPoolsExportsEnabled,
                               this.getConfigVals().metrics.garbageCollectorExportsEnabled,
                               this.getConfigVals().metrics.threadExportsEnabled,
                               this.getConfigVals().metrics.classLoadingExportsEnabled,
                               this.getConfigVals().metrics.versionInfoExportsEnabled);
    }
    else {
      logger.info("Metrics server disabled");
      this.metricsServer = null;
      this.metrics = null;
    }

    if (this.isZipkinEnabled()) {
      final ConfigVals.Proxy.Zipkin2 zipkin = this.getConfigVals().zipkin;
      final String zipkinHost = String.format("http://%s:%d/%s", zipkin.hostname, zipkin.port, zipkin.path);
      logger.info("Zipkin reporter enabled for {}", zipkinHost);
      this.zipkinReporter = new ZipkinReporter(zipkinHost, zipkin.serviceName);
    }
    else {
      logger.info("Zipkin reporter disabled");
      this.zipkinReporter = null;
    }

    this.grpcServer = isNullOrEmpty(inProcessServerName) ? ProxyGrpcServer.create(this, grpcPort)
                                                         : ProxyGrpcServer.create(this, inProcessServerName);
    this.httpServer = new ProxyHttpServer(this, httpPort);
  }

  public static void main(final String[] argv)
      throws IOException {
    logger.info(Utils.getBanner("banners/proxy.txt"));

    final ProxyArgs args = new ProxyArgs();
    args.parseArgs(Proxy.class.getName(), argv);

    final Config config = Utils.readConfig(args.config, PROXY_CONFIG, false);
    final ConfigVals configVals = new ConfigVals(config);
    args.assignArgs(configVals);

    final Proxy proxy = new Proxy(configVals,
                                  args.grpc_port,
                                  args.http_port,
                                  !args.disable_metrics,
                                  args.metrics_port,
                                  null);
    proxy.start();
    proxy.waitUntilShutdown();
  }

  public void start()
      throws IOException {
    this.grpcServer.start();

    this.httpServer.start();

    if (this.isMetricsEnabled())
      this.metricsServer.start();

    this.startStaleAgentCheck();

    Runtime.getRuntime()
           .addShutdownHook(
               new Thread(() -> {
                 System.err.println("*** Shutting down Proxy ***");
                 Proxy.this.stop();
                 System.err.println("*** Proxy shut down ***");
               }));
  }

  public void stop() {
    this.stopped.set(true);

    this.httpServer.stop();

    if (this.isMetricsEnabled())
      this.metricsServer.stop();

    if (this.isZipkinEnabled())
      this.getZipkinReporter().close();

    this.grpcServer.shutdown();
  }

  public void waitUntilShutdown() {
    try {
      this.grpcServer.awaitTermination();
    }
    catch (InterruptedException e) {
      // Ignore
    }
  }

  private void startStaleAgentCheck() {
    if (this.getConfigVals().internal.staleAgentCheckEnabled) {
      final long maxInactivitySecs = this.getConfigVals().internal.maxAgentInactivitySecs;
      final long threadPauseSecs = this.getConfigVals().internal.staleAgentCheckPauseSecs;
      logger.info("Agent eviction thread started ({} secs max inactivity secs with {} secs pause)",
                  maxInactivitySecs, threadPauseSecs);
      this.cleanupService.submit(() -> {
        while (!this.isStopped()) {
          this.agentContextMap
              .forEach((agentId, agentContext) -> {
                final long inactivitySecs = agentContext.inactivitySecs();
                if (inactivitySecs > maxInactivitySecs) {
                  logger.info("Evicting agent after {} secs of inactivty {}", inactivitySecs, agentContext);
                  removeAgentContext(agentId);
                  this.getMetrics().agentEvictions.inc();
                }
              });

          Utils.sleepForSecs(threadPauseSecs);
        }
      });
    }
    else {
      logger.info("Agent eviction thread not started");
    }
  }

  public boolean isStopped() { return this.stopped.get(); }

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
      logger.error("Missing AgentContext for agent_id: {}", agentId);

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
      logger.info("Added path /{} for {}", path, agentContext);
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
            logger.error("Missing path /{} for agent_id: {}", elem.getKey(), agentId);
        }
      }
    }
  }

  public int getAgentContextSize() { return this.agentContextMap.size(); }

  public int getPathMapSize() { return this.pathMap.size(); }

  public int getScrapeMapSize() { return this.scrapeRequestMap.size(); }

  public boolean isMetricsEnabled() { return this.metricsServer != null; }

  public ProxyMetrics getMetrics() { return this.metrics; }

  public boolean isZipkinEnabled() { return this.getConfigVals().zipkin.enabled; }

  public ZipkinReporter getZipkinReporter() { return this.zipkinReporter; }

  public Brave getBrave() { return this.getZipkinReporter().getBrave(); }

  public ConfigVals.Proxy getConfigVals() { return this.configVals.proxy; }

  public int getTotalAgentRequestQueueSize() {
    return this.agentContextMap.values()
                               .stream()
                               .mapToInt(AgentContext::scrapeRequestQueueSize)
                               .sum();
  }
}
