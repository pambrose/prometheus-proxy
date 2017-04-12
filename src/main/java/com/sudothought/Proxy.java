package com.sudothought;

import com.cinch.grpc.ScrapeRequest;
import com.google.common.collect.Maps;
import com.sudothought.args.ProxyArgs;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import spark.Spark;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Proxy {

  private static final Logger     logger              = Logger.getLogger(Proxy.class.getName());
  private static final AtomicLong SCRAPE_ID_GENERATOR = new AtomicLong(0);

  // Map agentId to AgentContext
  private final Map<Long, AgentContext>             agentContextMap    = Maps.newConcurrentMap();
  // Map path to agentId
  private final Map<String, Long>                   pathMap            = Maps.newConcurrentMap();
  // Map scrapeId to agentId
  private final Map<Long, ScrapeRequestContext>     scrapeRequestMap   = Maps.newConcurrentMap();

  private final int    port;
  private final Server grpcServer;

  public Proxy(final int grpcPort)
      throws IOException {
    this.port = grpcPort;
    this.grpcServer = ServerBuilder.forPort(this.port)
                                   .addService(new ProxyServiceImpl(this))
                                   .build()
                                   .start();
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
      final long agentId = proxy.pathMap.get(path);
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

      logger.log(Level.INFO, String.format("Results returned from agent for scrapeId: %s", scrapeId));


      res.status(scrapeRequestContext.getScrapeResponse().get().getStatusCode());
      res.type("text/plain");
      res.header("cache-control", "no-cache");

      return scrapeRequestContext.getScrapeResponse().get().getText();
    });

    proxy.blockUntilShutdown();
  }

  private void start()
      throws IOException {
    logger.info(String.format("gRPC server started listening on %s", port));
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

  public Map<Long, ScrapeRequestContext> getScrapeRequestMap() {
    return this.scrapeRequestMap;
  }

  public Map<Long, AgentContext> getAgentContextMap() {
    return this.agentContextMap;
  }

  public Map<String, Long> getPathMap() {
    return this.pathMap;
  }
}
