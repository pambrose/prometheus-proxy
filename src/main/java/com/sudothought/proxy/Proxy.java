package com.sudothought.proxy;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.grpc.BraveGrpcServerInterceptor;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sudothought.common.ConfigVals;
import com.sudothought.common.InstrumentedMap;
import com.sudothought.common.MetricsServer;
import com.sudothought.common.Utils;
import com.sudothought.common.ZipkinReporter;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigResolveOptions;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.prometheus.client.hotspot.DefaultExports;
import me.dinowernli.grpc.prometheus.Configuration;
import me.dinowernli.grpc.prometheus.MonitoringServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.sudothought.common.EnvVars.METRICS_PORT;
import static com.sudothought.common.EnvVars.PROXY_CONFIG;

public class Proxy {

  private static final Logger logger = LoggerFactory.getLogger(Proxy.class);

  private final AtomicBoolean   stopped         = new AtomicBoolean(false);
  private final ExecutorService executorService = Executors.newFixedThreadPool(1);

  private final ConfigVals                      configVals;
  private final MetricsServer                   metricsServer;
  private final ProxyMetrics                    metrics;
  private final Map<String, AgentContext>       agentContextMap;   // Map agent_id to AgentContext
  private final Map<String, AgentContext>       pathMap;           // Map path to AgentContext
  private final Map<Long, ScrapeRequestWrapper> scrapeRequestMap;  // Map scrape_id to agent_id
  private final ZipkinReporter                  zipkinReporter;
  private final Server                          grpcServer;
  private final HttpServer                      httpServer;

  private Proxy(final ConfigVals configVals, final int httpPort, final int metricsPort, final int grpcPort)
      throws IOException {
    this.configVals = configVals;

    if (this.isMetricsEnabled()) {
      this.metricsServer = new MetricsServer(metricsPort, this.getConfigVals().metrics.path);
      this.metrics = new ProxyMetrics();
      this.getMetrics().startTime.setToCurrentTime();
    }
    else {
      logger.info("Metrics endpoint disabled");
      this.metricsServer = null;
      this.metrics = null;
    }

    this.agentContextMap = this.isMetricsEnabled() ? new InstrumentedMap<>(Maps.newConcurrentMap(),
                                                                           this.getMetrics().agentMapSize)
                                                   : Maps.newConcurrentMap();
    this.pathMap = this.isMetricsEnabled() ? new InstrumentedMap<>(Maps.newConcurrentMap(),
                                                                   this.getMetrics().pathMapSize)
                                           : Maps.newConcurrentMap();
    this.scrapeRequestMap = this.isMetricsEnabled() ? new InstrumentedMap<>(Maps.newConcurrentMap(),
                                                                            this.getMetrics().scrapeMapSize)
                                                    : Maps.newConcurrentMap();

    if (this.getConfigVals().zipkin.enabled) {
      final ConfigVals.Proxy.Zipkin2 zipkin = this.getConfigVals().zipkin;
      final String zipkinHost = String.format("http://%s:%d/%s", zipkin.hostname, zipkin.port, zipkin.path);
      logger.info("Creating zipkin reporter for {}", zipkinHost);
      this.zipkinReporter = new ZipkinReporter(zipkinHost, zipkin.serviceName);
    }
    else {
      logger.info("Zipkin reporter disabled");
      this.zipkinReporter = null;
    }

    final List<ServerInterceptor> interceptors = Lists.newArrayList(new ProxyInterceptor());
    if (this.getConfigVals().grpc.prometheusMetricsEnabled)
      interceptors.add(MonitoringServerInterceptor.create(this.getConfigVals().grpc.allPrometheusMetrics
                                                          ? Configuration.allMetrics()
                                                          : Configuration.cheapMetricsOnly()));
    if (this.isZipkinReportingEnabled() && this.getConfigVals().grpc.zipkinReportingEnabled)
      interceptors.add(BraveGrpcServerInterceptor.create(this.getZipkinReporter().getBrave()));

    final ProxyServiceImpl proxyService = new ProxyServiceImpl(this);
    final ServerServiceDefinition serviceDef = ServerInterceptors.intercept(proxyService.bindService(), interceptors);
    this.grpcServer = ServerBuilder.forPort(grpcPort)
                                   .addService(serviceDef)
                                   .addTransportFilter(new ProxyTransportFilter(this))
                                   .build();

    this.httpServer = new HttpServer(this, httpPort);

    if (this.isMetricsEnabled()) {
      if (this.getConfigVals().internal.agentQueueSizeMetricsEnabled)
        this.executorService.submit(() -> {
          while (!this.isStopped()) {
            final int size = this.agentContextMap.values()
                                                 .stream()
                                                 .mapToInt(AgentContext::scrapeRequestQueueSize)
                                                 .sum();
            this.getMetrics().cummulativeAgentRequestQueueSize.set(size);

            try {
              Thread.sleep(this.getConfigVals().internal.agentQueueSizePauseSecs);
            }
            catch (InterruptedException e) {
              // Ignore
            }
          }
        });
      else
        this.getMetrics().cummulativeAgentRequestQueueSize.set(0);
    }
  }

  public static void main(final String[] argv)
      throws IOException {
    logger.info(Utils.getBanner("banners/proxy.txt"));

    final ProxyArgs args = new ProxyArgs();
    args.parseArgs(Proxy.class.getName(), argv);

    final Config config = Utils.readConfig(args.config,
                                           PROXY_CONFIG,
                                           ConfigParseOptions.defaults().setAllowMissing(false),
                                           ConfigFactory.load().resolve(),
                                           false)
                               .resolve(ConfigResolveOptions.defaults());

    final ConfigVals configVals = new ConfigVals(config);
    if (args.http_port == null)
      args.http_port = configVals.proxy.http.port;

    if (args.metrics_port == null)
      args.metrics_port = System.getenv(METRICS_PORT) == null
                          ? configVals.proxy.metrics.port
                          : Utils.getEnvInt(METRICS_PORT, true).orElse(configVals.proxy.metrics.port);

    if (args.grpc_port == null)
      args.grpc_port = configVals.proxy.grpc.port;

    final Proxy proxy = new Proxy(configVals, args.http_port, args.metrics_port, args.grpc_port);
    proxy.start();
    proxy.waitUntilShutdown();
  }

  private void start()
      throws IOException {
    this.grpcServer.start();
    logger.info("Started gRPC server listening on {}", this.grpcServer.getPort());

    this.httpServer.start();
    if (this.isMetricsEnabled())
      this.metricsServer.start();

    DefaultExports.initialize();

    Runtime.getRuntime()
           .addShutdownHook(
               new Thread(() -> {
                 System.err.println("*** Shutting down Proxy ***");
                 Proxy.this.stop();
                 System.err.println("*** Proxy shut down ***");
               }));
  }

  private void stop() {
    this.stopped.set(true);
    this.httpServer.stop();
    if (this.isMetricsEnabled())
      this.metricsServer.stop();
    if (this.isZipkinReportingEnabled())
      this.getZipkinReporter().close();
    this.grpcServer.shutdown();

    this.executorService.shutdownNow();
  }

  private void waitUntilShutdown() {
    try {
      this.grpcServer.awaitTermination();
    }
    catch (InterruptedException e) {
      // Ignore
    }
  }

  public boolean isStopped() { return this.stopped.get(); }

  public void addAgentContext(final AgentContext agentContext) {
    this.agentContextMap.put(agentContext.getAgentId(), agentContext);
  }

  public AgentContext getAgentContext(String agentId) { return this.agentContextMap.get(agentId); }

  public AgentContext removeAgentContext(String agentId) {
    final AgentContext agentContext = this.agentContextMap.remove(agentId);
    if (agentContext != null)
      logger.info("Removed {}", agentContext);
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

  public boolean isMetricsEnabled() { return this.getConfigVals().metrics.enabled; }

  public ProxyMetrics getMetrics() { return this.metrics; }

  public boolean isZipkinReportingEnabled() { return this.getZipkinReporter() != null; }

  public ZipkinReporter getZipkinReporter() { return this.zipkinReporter; }

  public Brave getBrave() { return this.getZipkinReporter().getBrave(); }

  public ConfigVals.Proxy getConfigVals() { return this.configVals.proxy; }
}
