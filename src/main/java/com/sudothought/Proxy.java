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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class Proxy {

  public static final String                 AGENT_ID        = "agent-id";
  public static final Attributes.Key<String> ATTRIB_AGENT_ID = Attributes.Key.of(AGENT_ID);

  private static final org.slf4j.Logger logger              = LoggerFactory.getLogger(Proxy.class);
  private static final AtomicLong       SCRAPE_ID_GENERATOR = new AtomicLong(0);

  private final AtomicBoolean                   stopped          = new AtomicBoolean(false);
  // Map agent_id to AgentContext
  private final Map<String, AgentContext>       agentContextMap  = Maps.newConcurrentMap();
  // Map path to agent_id
  private final Map<String, String>             pathMap          = Maps.newConcurrentMap();
  // Map scrape_id to agent_id
  private final Map<Long, ScrapeRequestContext> scrapeRequestMap = Maps.newConcurrentMap();

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
                                       final Attributes.Key<String> remote_addr_key = Attributes.Key.of("remote-addr");
                                       final Optional<Attributes.Key<?>> key_opt = attributes.keys()
                                                                                             .stream()
                                                                                             .filter(key -> key.toString().equals("remote-addr"))
                                                                                             .findFirst();
                                       final Attributes.Key<Object> key = (Attributes.Key<Object>) key_opt.get();
                                       final Object remote_addr = attributes.get(key);
                                       final AgentContext agentContext = new AgentContext(remote_addr.toString());
                                       final String agent_id = agentContext.getAgentId();
                                       agentContextMap.put(agent_id, agentContext);
                                       logger.info("Connection from {} agent_id: {}", remote_addr, agent_id);
                                       return Attributes.newBuilder()
                                                        .set(ATTRIB_AGENT_ID, agent_id)
                                                        .setAll(attributes)
                                                        .build();
                                     }

                                     @Override
                                     public void transportTerminated(Attributes attributes) {
                                       final String agent_id = attributes.get(ATTRIB_AGENT_ID);
                                       final AgentContext agentContext = agentContextMap.remove(agent_id);
                                       logger.info("Disconnection from {} agent_id: {}",
                                                   agentContext.getRemoteAddr(), agent_id);
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
    logger.info("Started proxy listening on {}", proxyArgs.http_port);

    Spark.get("/*", (req, res) -> {
      res.header("cache-control", "no-cache");

      final String path = req.splat()[0];
      final String agent_id = proxy.pathMap.get(path);

      if (agent_id == null) {
        logger.info("Missing path request /{}", path);
        res.status(404);
        return null;
      }

      final AgentContext agentContext = proxy.getAgentContextMap().get(agent_id);
      if (agentContext == null) {
        proxy.getAgentContextMap().remove(agent_id);
        logger.info("Missing AgentContext /{} agent_id: {}", path, agent_id);
        res.status(404);
        return null;
      }

      final long scrape_id = SCRAPE_ID_GENERATOR.getAndIncrement();
      final ScrapeRequest scrapeRequest = ScrapeRequest.newBuilder()
                                                       .setAgentId(agent_id)
                                                       .setScrapeId(scrape_id)
                                                       .setPath(path)
                                                       .build();
      final ScrapeRequestContext scrapeRequestContext = new ScrapeRequestContext(scrapeRequest);

      proxy.getScrapeRequestMap().put(scrape_id, scrapeRequestContext);
      agentContext.getScrapeRequestQueue().add(scrapeRequestContext);

      while (true) {
        if (scrapeRequestContext.waitUntilComplete()) {
          break;
        }
        else {
          // Check if agent is disconnected or agent is hung
          if (!proxy.isValidAgentId(agent_id) || scrapeRequestContext.ageInSecs() >= 5 || proxy.isStopped()) {
            res.status(503);
            return null;
          }
        }
      }

      logger.info("Results returned from agent for scrape_id: {}", scrape_id);

      final int status_code = scrapeRequestContext.getScrapeResponse().get().getStatusCode();
      res.status(status_code);

      if (status_code >= 400) {
        return null;
      }
      else {
        res.type("text/plain");
        return scrapeRequestContext.getScrapeResponse().get().getText();
      }

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
    this.stopped.set(true);
    if (this.grpcServer != null)
      this.grpcServer.shutdown();
    Spark.stop();
  }

  private void blockUntilShutdown()
      throws InterruptedException {
    if (this.grpcServer != null)
      this.grpcServer.awaitTermination();
  }

  public boolean isValidAgentId(final String agentId) {return this.getAgentContextMap().containsKey(agentId);}

  public boolean isStopped() { return this.stopped.get(); }

  public Map<Long, ScrapeRequestContext> getScrapeRequestMap() { return this.scrapeRequestMap; }

  public Map<String, AgentContext> getAgentContextMap() { return this.agentContextMap; }

  public Map<String, String> getPathMap() { return this.pathMap; }
}
