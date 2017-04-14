package com.sudothought.proxy;

import com.google.common.collect.Maps;
import com.sudothought.agent.AgentContext;
import com.sudothought.args.ProxyArgs;
import com.sudothought.common.MetricsServer;
import io.grpc.Attributes;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.prometheus.client.hotspot.DefaultExports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class Proxy {

  public static final String                 AGENT_ID        = "agent-id";
  public static final Attributes.Key<String> ATTRIB_AGENT_ID = Attributes.Key.of(AGENT_ID);

  private static final Logger logger = LoggerFactory.getLogger(Proxy.class);


  private final AtomicBoolean                   stopped          = new AtomicBoolean(false);
  // Map agent_id to AgentContext
  private final Map<String, AgentContext>       agentContextMap  = Maps.newConcurrentMap();
  // Map path to agent_id
  private final Map<String, String>             pathMap          = Maps.newConcurrentMap();
  // Map scrape_id to agent_id
  private final Map<Long, ScrapeRequestContext> scrapeRequestMap = Maps.newConcurrentMap();

  private final HttpServer    httpServer;
  private final int           grpcPort;
  private final Server        grpcServer;
  private final MetricsServer metricsServer;

  private Proxy(final int proxyPort, final int metricsPort, final int grpcPort)
      throws IOException {
    this.grpcPort = grpcPort;
    final ProxyServiceImpl proxyService = new ProxyServiceImpl(this);
    final ServerInterceptor interceptor = new ProxyInterceptor();
    final ServerServiceDefinition serviceDef = ServerInterceptors.intercept(proxyService.bindService(), interceptor);
    this.grpcServer = ServerBuilder.forPort(this.grpcPort)
                                   .addService(serviceDef)
                                   .addTransportFilter(new ProxyTransportFilter(this))
                                   .build();
    this.httpServer = new HttpServer(this, proxyPort);
    this.metricsServer = new MetricsServer(metricsPort);
  }


  public static void main(final String[] argv)
      throws IOException {
    final ProxyArgs proxyArgs = new ProxyArgs();
    proxyArgs.parseArgs(Proxy.class.getName(), argv);

    final Proxy proxy = new Proxy(proxyArgs.http_port, proxyArgs.metrics_port, proxyArgs.grpc_port);
    proxy.start();
    proxy.waitUntilShutdown();
  }

  private void start()
      throws IOException {
    this.grpcServer.start();
    logger.info("Started gRPC server listening on {}", this.grpcPort);

    this.httpServer.start();

    this.metricsServer.start();

    DefaultExports.initialize();

    Runtime.getRuntime()
           .addShutdownHook(
               new Thread(() -> {
                 System.err.println("*** Shutting down Proxy since JVM is shutting down");
                 Proxy.this.stop();
                 System.err.println("*** Proxy shut down");
               }));
  }

  private void stop() {
    this.stopped.set(true);
    this.grpcServer.shutdown();
    this.httpServer.stop();
    this.metricsServer.stop();
  }

  private void waitUntilShutdown() {
    try {
      this.grpcServer.awaitTermination();
    }
    catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public boolean isValidAgentId(final String agentId) {return this.agentContextMap.containsKey(agentId);}

  public boolean isStopped() { return this.stopped.get(); }

  public Map<String, String> getPathMap() { return this.pathMap; }

  public void addAgentContext(final String agentId, final AgentContext agentContext) {
    this.agentContextMap.put(agentId, agentContext);
    ProxyMetrics.CONNECTED_AGENTS.inc();
  }

  public AgentContext getAgentContext(String agentId) { return this.agentContextMap.get(agentId); }

  public AgentContext removeAgentContext(String agentId) {
    ProxyMetrics.CONNECTED_AGENTS.dec();
    return this.agentContextMap.remove(agentId);
  }

  public void addScrapeRequest(long scrapeId, ScrapeRequestContext scrapeRequestContext) {
    this.scrapeRequestMap.put(scrapeId, scrapeRequestContext);
    ProxyMetrics.SCRAPE_REQUESTS.observe(1);
  }

  public ScrapeRequestContext removeScrapeRequest(long scrapeId) {
    return this.scrapeRequestMap.remove(scrapeId);
  }
}
