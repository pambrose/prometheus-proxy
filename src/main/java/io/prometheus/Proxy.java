package io.prometheus;

import com.beust.jcommander.JCommander;
import com.github.kristofa.brave.Brave;
import com.google.common.collect.Maps;
import io.grpc.Attributes;
import io.prometheus.common.ConfigVals;
import io.prometheus.common.MetricsServer;
import io.prometheus.common.SystemMetrics;
import io.prometheus.common.Utils;
import io.prometheus.common.ZipkinReporter;
import io.prometheus.grpc.UnregisterPathResponse;
import io.prometheus.proxy.AgentContext;
import io.prometheus.proxy.ProxyGrpcServer;
import io.prometheus.proxy.ProxyHttpServer;
import io.prometheus.proxy.ProxyMetrics;
import io.prometheus.proxy.ProxyOptions;
import io.prometheus.proxy.ScrapeRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;

public class Proxy
    implements Closeable {

  private static final Logger logger = LoggerFactory.getLogger(Proxy.class);

  public static final String                          AGENT_ID         = "agent-id";
  public static final Attributes.Key<String>          ATTRIB_AGENT_ID  = Attributes.Key.of(AGENT_ID);
  private final       AtomicBoolean                   stopped          = new AtomicBoolean(false);
  private final       Map<String, AgentContext>       agentContextMap  = Maps.newConcurrentMap(); // Map agent_id to AgentContext
  private final       Map<String, AgentContext>       pathMap          = Maps.newConcurrentMap(); // Map path to AgentContext
  private final       Map<Long, ScrapeRequestWrapper> scrapeRequestMap = Maps.newConcurrentMap(); // Map scrape_id to agent_id
  private final       ExecutorService                 cleanupService   = Executors.newFixedThreadPool(1);

  private final ConfigVals      configVals;
  private final MetricsServer   metricsServer;
  private final ProxyMetrics    metrics;
  private final ZipkinReporter  zipkinReporter;
  private final ProxyGrpcServer grpcServer;
  private final ProxyHttpServer httpServer;
  private final boolean         testMode;

  public Proxy(final ConfigVals configVals,
               final int grpcPort,
               final int httpPort,
               final boolean metricsEnabled,
               final int metricsPort,
               final String inProcessServerName,
               final boolean testMode)
      throws IOException {
    this.configVals = configVals;
    this.testMode = testMode;

    if (metricsEnabled) {
      logger.info("Metrics server enabled with {} /{}", metricsPort, this.getConfigVals().metrics.path);
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
      final ConfigVals.Proxy2.Internal2.Zipkin2 zipkin = this.getConfigVals().internal.zipkin;
      final String zipkinHost = format("http://%s:%d/%s", zipkin.hostname, zipkin.port, zipkin.path);
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
      throws IOException, InterruptedException {
    final ProxyOptions options = new ProxyOptions(Proxy.class.getName(), argv);

    logger.info(Utils.getBanner("banners/proxy.txt"));
    logger.info(Utils.getVersionDesc());

    final Proxy proxy = new Proxy(options.getConfigVals(),
                                  options.getAgentPort(),
                                  options.getProxyPort(),
                                  options.getEnableMetrics(),
                                  options.getMetricsPort(),
                                  null,
                                  false);
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
                 JCommander.getConsole().println("*** Shutting down Proxy ***");
                 Proxy.this.stop();
                 JCommander.getConsole().println("*** Proxy shut down ***");
               }));
  }

  @Override
  public void close()
      throws IOException {
    this.stop();
  }

  public void stop() {
    if (this.stopped.compareAndSet(false, true)) {
      this.cleanupService.shutdownNow();
      this.httpServer.stop();

      if (this.isMetricsEnabled())
        this.metricsServer.stop();

      if (this.isZipkinEnabled())
        this.getZipkinReporter().close();

      this.grpcServer.shutdown();
    }
  }

  public void waitUntilShutdown()
      throws InterruptedException {
    this.grpcServer.awaitTermination();
  }

  public void waitUntilShutdown(final long timeout, final TimeUnit unit)
      throws InterruptedException {
    this.grpcServer.awaitTermination(timeout, unit);
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
      if (!this.testMode)
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
        if (!this.testMode)
          logger.info("Removed path /{} for {}", path, agentContext);
        responseBuilder.setValid(true).setReason("");
      }
    }
  }

  public int pathMapSize() { return this.pathMap.size(); }

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

  public int getAgentContextSize() { return this.agentContextMap.size(); }

  public int getPathMapSize() { return this.pathMap.size(); }

  public int getScrapeMapSize() { return this.scrapeRequestMap.size(); }

  public boolean isMetricsEnabled() { return this.metricsServer != null; }

  public ProxyMetrics getMetrics() { return this.metrics; }

  public boolean isZipkinEnabled() { return this.getConfigVals().internal.zipkin.enabled; }

  public ZipkinReporter getZipkinReporter() { return this.zipkinReporter; }

  public Brave getBrave() { return this.getZipkinReporter().getBrave(); }

  public ConfigVals.Proxy2 getConfigVals() { return this.configVals.proxy; }

  public int getTotalAgentRequestQueueSize() {
    return this.agentContextMap.values()
                               .stream()
                               .mapToInt(AgentContext::scrapeRequestQueueSize)
                               .sum();
  }
}
