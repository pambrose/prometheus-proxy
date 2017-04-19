package com.sudothought.agent;

import com.github.kristofa.brave.grpc.BraveGrpcClientInterceptor;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.RateLimiter;
import com.google.protobuf.Empty;
import com.sudothought.common.ConfigVals;
import com.sudothought.common.InstrumentedBlockingQueue;
import com.sudothought.common.MetricsServer;
import com.sudothought.common.Utils;
import com.sudothought.common.ZipkinReporter;
import com.sudothought.grpc.AgentInfo;
import com.sudothought.grpc.ProxyServiceGrpc;
import com.sudothought.grpc.RegisterAgentRequest;
import com.sudothought.grpc.RegisterAgentResponse;
import com.sudothought.grpc.RegisterPathRequest;
import com.sudothought.grpc.RegisterPathResponse;
import com.sudothought.grpc.ScrapeRequest;
import com.sudothought.grpc.ScrapeResponse;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigResolveOptions;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.prometheus.client.Summary;
import io.prometheus.client.hotspot.DefaultExports;
import me.dinowernli.grpc.prometheus.Configuration;
import me.dinowernli.grpc.prometheus.MonitoringClientInterceptor;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.sudothought.common.EnvVars.AGENT_CONFIG;
import static com.sudothought.common.Utils.newInstrumentedThreadFactory;
import static com.sudothought.grpc.ProxyServiceGrpc.newBlockingStub;
import static com.sudothought.grpc.ProxyServiceGrpc.newStub;
import static io.grpc.ClientInterceptors.intercept;
import static java.util.concurrent.Executors.newCachedThreadPool;

public class Agent {

  private static final Logger logger        = LoggerFactory.getLogger(Agent.class);

  private final AtomicBoolean            stopped         = new AtomicBoolean(false);
  private final Map<String, PathContext> pathContextMap  = Maps.newConcurrentMap();  // Map path to PathContext
  private final AtomicReference<String>  agentIdRef      = new AtomicReference<>();
  private final ExecutorService          executorService = newCachedThreadPool(newInstrumentedThreadFactory("agent_fetch",
                                                                                                            "Agent fetch",
                                                                                                            true));
  private final ConfigVals                                configVals;
  private final AgentMetrics                              metrics;
  private final BlockingQueue<ScrapeResponse>             scrapeResponseQueue;
  private final RateLimiter                               reconnectLimiter;
  private final MetricsServer                             metricsServer;
  private final List<Map<String, String>>                 pathConfigs;
  private final String                                    host;
  private final ManagedChannel                            channel;
  private final ZipkinReporter                            zipkinReporter;
  private final ProxyServiceGrpc.ProxyServiceBlockingStub blockingStub;
  private final ProxyServiceGrpc.ProxyServiceStub         asyncStub;

  private Agent(final ConfigVals configVals, final String host, final int metricsPort) {
    this.configVals = configVals;

    if (this.getConfigVals().metrics.enabled) {
      this.metricsServer = new MetricsServer(metricsPort, this.getConfigVals().metrics.path);
      this.metrics = new AgentMetrics();
      this.metrics.startTime.setToCurrentTime();
    }
    else {
      logger.info("Metrics endpoint disabled");
      this.metricsServer = null;
      this.metrics = null;
    }

    this.scrapeResponseQueue = this.metrics != null && this.getConfigVals().scrape.metricsEnabled
                               ? new InstrumentedBlockingQueue<>(new ArrayBlockingQueue<>(this.getConfigVals().scrape.queueSize),
                                                                 this.metrics.scrapeQueueSize)
                               : new ArrayBlockingQueue<>(this.getConfigVals().scrape.queueSize);

    logger.info("Assigning proxy reconnect pause time to {} secs", this.getConfigVals().grpc.reconectPauseSecs);
    this.reconnectLimiter = RateLimiter.create(1.0 / this.getConfigVals().grpc.reconectPauseSecs);

    this.pathConfigs = configVals.agent.pathConfigs.stream()
                                                   .map(v -> ImmutableMap.of("name", v.name,
                                                                             "path", v.path,
                                                                             "url", v.url))
                                                   .peek(v -> logger.info("Proxy path /{} will be assigned to {}",
                                                                          v.get("path"), v.get("url")))
                                                   .collect(Collectors.toList());

    if (this.getConfigVals().zipkin.enabled) {
      final ConfigVals.Agent.Zipkin zipkin = this.getConfigVals().zipkin;
      final String zipkinHost = String.format("http://%s:%d/%s", zipkin.hostname, zipkin.port, zipkin.path);
      logger.info("Creating zipkin reporter for {}", zipkinHost);
      this.zipkinReporter = new ZipkinReporter(zipkinHost, zipkin.serviceName);
    }
    else {
      logger.info("Zipkin reporter disabled");
      this.zipkinReporter = null;
    }

    final String hostname;
    final int port;
    if (host.contains(":")) {
      String[] vals = host.split(":");
      hostname = vals[0];
      port = Integer.valueOf(vals[1]);
    }
    else {
      hostname = host;
      port = 50051;
    }
    this.host = String.format("%s:%s", hostname, port);
    this.channel = ManagedChannelBuilder.forAddress(hostname, port).usePlaintext(true).build();

    final List<ClientInterceptor> interceptors = Lists.newArrayList(new AgentClientInterceptor(this));
    if (this.getConfigVals().grpc.metricsEnabled)
      interceptors.add(MonitoringClientInterceptor.create(this.getConfigVals().grpc.allMetrics
                                                          ? Configuration.allMetrics()
                                                          : Configuration.cheapMetricsOnly()));
    if (this.zipkinReporter != null)
      interceptors.add(BraveGrpcClientInterceptor.create(this.zipkinReporter.getBrave()));

    this.blockingStub = newBlockingStub(intercept(this.channel, interceptors));
    this.asyncStub = newStub(intercept(this.channel, interceptors));
  }

  public static void main(final String[] argv)
      throws IOException {
    logger.info(Utils.getBanner("banners/agent.txt"));

    final AgentArgs args = new AgentArgs();
    args.parseArgs(Agent.class.getName(), argv);

    final Config config = Utils.readConfig(args.config,
                                           AGENT_CONFIG, ConfigParseOptions.defaults()
                                                                           .setAllowMissing(false),
                                           ConfigFactory.load(),
                                           true)
                               .resolve(ConfigResolveOptions.defaults());
    final ConfigVals configVals = new ConfigVals(config.withFallback(ConfigFactory.load())
                                                       .resolve(ConfigResolveOptions.defaults()));
    if (args.proxy_host == null)
      args.proxy_host = String.format("%s:%d", configVals.agent.grpc.hostname, configVals.agent.grpc.port);

    if (args.metrics_port == null)
      args.metrics_port = configVals.agent.metrics.port;

    final Agent agent = new Agent(configVals, args.proxy_host, args.metrics_port);
    agent.run();
  }

  private static String getHostName() {
    try {
      final String hostname = InetAddress.getLocalHost().getHostName();
      final String address = InetAddress.getLocalHost().getHostAddress();
      return hostname;
    }
    catch (UnknownHostException e) {
      return "Unknown";
    }
  }

  private void run()
      throws IOException {
    if (this.metricsServer != null) {
      this.metricsServer.start();
      DefaultExports.initialize();
    }

    // Prime the limiter
    this.reconnectLimiter.acquire();

    Runtime.getRuntime()
           .addShutdownHook(
               new Thread(() -> {
                 System.err.println("*** Shutting down Agent ***");
                 Agent.this.stop();
                 System.err.println("*** Agent shut down ***");
               }));

    while (!this.isStopped()) {
      final AtomicBoolean connected = new AtomicBoolean(false);
      final AtomicBoolean disconnected = new AtomicBoolean(false);
      // Reset values for each connection attempt
      this.setAgentId(null);
      this.pathContextMap.clear();
      this.scrapeResponseQueue.clear();

      try {
        logger.info("Connecting to proxy at {}...", this.host);
        this.connectAgent();
        logger.info("Connected to proxy at {}", this.host);
        connected.set(true);

        this.registerAgent();
        this.registerPaths();

        this.asyncStub
            .readRequestsFromProxy(AgentInfo.newBuilder().setAgentId(this.getAgentId()).build(),
                                   new StreamObserver<ScrapeRequest>() {
                                     @Override
                                     public void onNext(final ScrapeRequest scrapeRequest) {
                                       executorService.submit(
                                           () -> {
                                             final ScrapeResponse scrapeResponse = fetchMetrics(scrapeRequest);
                                             try {
                                               scrapeResponseQueue.put(scrapeResponse);
                                             }
                                             catch (InterruptedException e) {
                                               // Ignore
                                             }
                                           });
                                     }

                                     @Override
                                     public void onError(Throwable t) {
                                       final Status status = Status.fromThrowable(t);
                                       logger.info("Error in readRequestsFromProxy(): {}", status);
                                       disconnected.set(true);
                                     }

                                     @Override
                                     public void onCompleted() {
                                       disconnected.set(true);
                                     }
                                   });

        final StreamObserver<ScrapeResponse> responseObserver = this.asyncStub.writeResponsesToProxy(
            new StreamObserver<Empty>() {
              @Override
              public void onNext(Empty rmpty) {
                // Ignore
              }

              @Override
              public void onError(Throwable t) {
                final Status status = Status.fromThrowable(t);
                logger.info("Error in writeResponsesToProxy(): {}", status);
                disconnected.set(true);
              }

              @Override
              public void onCompleted() {
                disconnected.set(true);
              }
            });

        while (!disconnected.get()) {
          try {
            // Set a short timeout to check if client has disconnected
            final ScrapeResponse response = this.scrapeResponseQueue.poll(1, TimeUnit.SECONDS);
            if (response != null)
              responseObserver.onNext(response);
          }
          catch (InterruptedException e) {
            // Ignore
          }
        }

        // responseObserver.onCompleted();
      }
      catch (ConnectException e) {
        // Ignore
      }
      catch (StatusRuntimeException e) {
        logger.info("Cannot connect to proxy at {} [{}]", this.host, e.getMessage());
      }

      if (connected.get())
        logger.info("Disconnected from proxy at {}", this.host);

      final double secsWaiting = this.reconnectLimiter.acquire();
      logger.info("Waited {} secs to reconnect", secsWaiting);
    }
  }

  private ScrapeResponse fetchMetrics(final ScrapeRequest scrapeRequest) {
    int status_code = 404;
    final String path = scrapeRequest.getPath();
    final ScrapeResponse.Builder scrapeResponse = ScrapeResponse.newBuilder()
                                                                .setAgentId(scrapeRequest.getAgentId())
                                                                .setScrapeId(scrapeRequest.getScrapeId());
    final PathContext pathContext = this.pathContextMap.get(path);
    if (pathContext == null) {
      logger.warn("Invalid path request: {}", path);
      if (this.metrics != null)
        this.metrics.scrapeRequests.labels("invalid_path").observe(1);
      return scrapeResponse.setValid(false)
                           .setStatusCode(status_code)
                           .setText("")
                           .setContentType("")
                           .build();
    }

    final Summary.Timer requestTimer = this.metrics != null ? this.metrics.scrapeRequestLatency.startTimer() : null;
    try {
      logger.info("Fetching path request /{} {}", path, pathContext.getUrl());
      final Response response = pathContext.fetchUrl(scrapeRequest);
      status_code = response.code();
      if (response.isSuccessful()) {
        if (this.metrics != null)
          this.metrics.scrapeRequests.labels("success").observe(1);
        return scrapeResponse.setValid(true)
                             .setStatusCode(status_code)
                             .setText(response.body().string())
                             .setContentType(response.header(CONTENT_TYPE))
                             .build();
      }
    }
    catch (IOException e) {
      // Ignore
    }
    finally {
      if (requestTimer != null)
        requestTimer.observeDuration();
    }
    if (this.metrics != null)
      this.metrics.scrapeRequests.labels("unsuccessful").observe(1);
    return scrapeResponse.setValid(false)
                         .setStatusCode(status_code)
                         .setText("")
                         .setContentType("")
                         .build();
  }

  public void stop() {
    this.stopped.set(true);
    if (this.metricsServer != null)
      this.metricsServer.stop();
    if (this.zipkinReporter != null)
      this.zipkinReporter.close();
    try {
      this.channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
    }
    catch (InterruptedException e) {
      // Ignore
    }
  }

  // If successful, this will create an agentContxt on the Proxy and an interceptor will
  // add an agent_id to the headers
  private void connectAgent() { this.blockingStub.connectAgent(Empty.getDefaultInstance()); }

  private void registerAgent()
      throws ConnectException {
    final RegisterAgentRequest request = RegisterAgentRequest.newBuilder()
                                                             .setAgentId(this.getAgentId())
                                                             .setHostname(getHostName())
                                                             .build();
    final RegisterAgentResponse response = this.blockingStub.registerAgent(request);
    if (!response.getValid())
      throw new ConnectException("registerAgent()");
  }

  private void registerPaths()
      throws ConnectException {
    for (Map<String, String> agentConfig : this.pathConfigs) {
      final String path = agentConfig.get("path");
      final String url = agentConfig.get("url");
      final long pathId = this.registerPath(path);
      logger.info("Registered {} as /{}", url, path);
      this.pathContextMap.put(path, new PathContext(pathId, path, url));
    }
  }

  private long registerPath(final String path)
      throws ConnectException {
    final RegisterPathRequest request = RegisterPathRequest.newBuilder()
                                                           .setAgentId(this.getAgentId())
                                                           .setPath(path)
                                                           .build();
    final RegisterPathResponse response = this.blockingStub.registerPath(request);
    if (!response.getValid())
      throw new ConnectException("registerPath()");
    return response.getPathId();
  }

  private boolean isStopped() { return this.stopped.get(); }

  public ManagedChannel getChannel() { return this.channel; }

  public String getAgentId() { return this.agentIdRef.get(); }

  public void setAgentId(final String agentId) { this.agentIdRef.set(agentId); }

  public ConfigVals.Agent getConfigVals() { return this.configVals.agent; }
}
