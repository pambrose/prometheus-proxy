package com.sudothought;

import com.google.common.collect.Maps;
import com.sudothought.args.ProxyArgs;
import io.grpc.Attributes;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerTransportFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
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

  private final int        grpcPort;
  private final Server     grpcServer;
  private final int        httpPort;
  private final HttpServer httpServer;

  public Proxy(final int grpcPort, final int httpPort)
      throws IOException {
    this.grpcPort = grpcPort;
    this.httpPort = httpPort;
    final ProxyServiceImpl proxyService = new ProxyServiceImpl(this);
    final ServerInterceptor interceptor = new ProxyInterceptor();
    final ServerServiceDefinition serviceDef = ServerInterceptors.intercept(proxyService.bindService(), interceptor);
    this.grpcServer = ServerBuilder.forPort(this.grpcPort)
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

    this.httpServer = new HttpServer(this, this.httpPort);

  }

  public static void main(final String[] argv)
      throws Exception {

    final ProxyArgs proxyArgs = new ProxyArgs();
    proxyArgs.parseArgs(Proxy.class.getName(), argv);

    final Proxy proxy = new Proxy(proxyArgs.grpc_port, proxyArgs.http_port);
    proxy.start();
    proxy.waitUntilShutdown();
  }

  private void start()
      throws IOException {
    this.grpcServer.start();
    logger.info("Started gRPC server listening on {}", grpcPort);
    this.httpServer.start();
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
  }

  private void waitUntilShutdown()
      throws InterruptedException {
    this.grpcServer.awaitTermination();
  }

  public boolean isValidAgentId(final String agentId) {return this.getAgentContextMap().containsKey(agentId);}

  public boolean isStopped() { return this.stopped.get(); }

  public Map<Long, ScrapeRequestContext> getScrapeRequestMap() { return this.scrapeRequestMap; }

  public Map<String, AgentContext> getAgentContextMap() { return this.agentContextMap; }

  public Map<String, String> getPathMap() { return this.pathMap; }
}
