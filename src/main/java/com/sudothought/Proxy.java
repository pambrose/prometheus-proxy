package com.sudothought;

import com.google.common.collect.Maps;
import com.sudothought.args.ProxyArgs;
import com.sudothought.grpc.ScrapeRequest;
import io.grpc.Attributes;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerTransportFilter;
import org.slf4j.LoggerFactory;
import spark.Spark;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class Proxy {

  public static final  String                          AGENT_ID            = "agent-id";
  public static final  Attributes.Key<String>          ATTRIB_AGENT_ID     = Attributes.Key.of(AGENT_ID);
  private static final org.slf4j.Logger                logger              = LoggerFactory.getLogger(Proxy.class);
  private static final AtomicLong                      SCRAPE_ID_GENERATOR = new AtomicLong(0);
  // Map agentId to AgentContext
  private final        Map<String, AgentContext>       agentContextMap     = Maps.newConcurrentMap();
  // Map path to agentId
  private final        Map<String, String>             pathMap             = Maps.newConcurrentMap();
  // Map scrapeId to agentId
  private final        Map<Long, ScrapeRequestContext> scrapeRequestMap    = Maps.newConcurrentMap();

  private final int    port;
  private final Server grpcServer;

  public Proxy(final int grpcPort)
      throws IOException {
    this.port = grpcPort;
    final ProxyServiceImpl proxyService = new ProxyServiceImpl(this);
    final ServerInterceptor interceptor = new ProxyInterceptor();
    final ServerServiceDefinition serviceDef = ServerInterceptors.intercept(proxyService.bindService(), interceptor);
    this.grpcServer = ServerBuilder.forPort(this.port)
                                   .addService(serviceDef)
                                   .addTransportFilter(new ServerTransportFilter() {
                                     @Override
                                     public Attributes transportReady(Attributes attributes) {
                                       final AgentContext agentContext = new AgentContext(attributes.get(Attributes.Key.of("remote-addr")));
                                       agentContextMap.put(agentContext.getAgentId(), agentContext);
                                       return Attributes.newBuilder()
                                                        .set(ATTRIB_AGENT_ID, "" + agentContext.getAgentId())
                                                        .setAll(attributes)
                                                        .build();
                                     }

                                     @Override
                                     public void transportTerminated(Attributes attributes) {
                                       final String agent_id = attributes.get(ATTRIB_AGENT_ID);
                                       final AgentContext agentContext = agentContextMap.remove(agent_id);
                                       super.transportTerminated(attributes);
                                     }
                                   })
                                   .build();
  }

  public static void main(final String[] argv)
      throws Exception {

    final ProxyArgs proxyArgs = new ProxyArgs();
    proxyArgs.parseArgs(Proxy.class.getName(), argv);

    Proxy proxy = new Proxy(proxyArgs.grpc_port);
    proxy.start();

    // Start Http Server
    Spark.port(proxyArgs.http_port);
    Spark.get("/*", (req, res) -> {
      final String path = req.splat()[0];
      final String agentId = proxy.pathMap.get(path);
      final long scrapeId = SCRAPE_ID_GENERATOR.getAndIncrement();
      final ScrapeRequest scrapeRequest = ScrapeRequest.newBuilder()
                                                       .setAgentId(agentId)
                                                       .setScrapeId(scrapeId)
                                                       .setPath(path)
                                                       .build();
      final ScrapeRequestContext scrapeRequestContext = new ScrapeRequestContext(scrapeRequest);

      proxy.getScrapeRequestMap().put(scrapeId, scrapeRequestContext);
      final AgentContext agentContext = proxy.getAgentContextMap().get(agentId);
      agentContext.getScrapeRequestQueue().add(scrapeRequestContext);

      scrapeRequestContext.waitUntilComplete();

      logger.info("Results returned from agent for scrapeId: {}", scrapeId);

      res.status(scrapeRequestContext.getScrapeResponse().get().getStatusCode());
      res.type("text/plain");
      res.header("cache-control", "no-cache");

      return scrapeRequestContext.getScrapeResponse().get().getText();
    });

    proxy.blockUntilShutdown();
  }

  private void start()
      throws IOException {
    this.grpcServer.start();
    logger.info("Started gRPC server listening on {}", port);
    Runtime.getRuntime()
           .addShutdownHook(
               new Thread(() -> {
                 System.err.println("*** Shutting down gRPC server since JVM is shutting down");
                 Proxy.this.stop();
                 System.err.println("*** gRPC server shut down");
               }));
  }

  private void stop() {
    if (this.grpcServer != null)
      this.grpcServer.shutdown();
    Spark.stop();
  }

  private void blockUntilShutdown()
      throws InterruptedException {
    if (this.grpcServer != null)
      this.grpcServer.awaitTermination();
  }

  public Map<Long, ScrapeRequestContext> getScrapeRequestMap() { return this.scrapeRequestMap; }

  public Map<String, AgentContext> getAgentContextMap() { return this.agentContextMap; }

  public Map<String, String> getPathMap() { return this.pathMap; }
}
