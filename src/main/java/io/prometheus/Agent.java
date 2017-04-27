package io.prometheus;

import com.beust.jcommander.JCommander;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.RateLimiter;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.Empty;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.prometheus.agent.AgentClientInterceptor;
import io.prometheus.agent.AgentMetrics;
import io.prometheus.agent.AgentOptions;
import io.prometheus.agent.PathContext;
import io.prometheus.agent.RequestFailureException;
import io.prometheus.client.Summary;
import io.prometheus.common.ConfigVals;
import io.prometheus.common.GenericServiceListener;
import io.prometheus.common.MetricsServer;
import io.prometheus.common.SystemMetrics;
import io.prometheus.common.Utils;
import io.prometheus.common.ZipkinReporter;
import io.prometheus.grpc.AgentInfo;
import io.prometheus.grpc.HeartBeatRequest;
import io.prometheus.grpc.HeartBeatResponse;
import io.prometheus.grpc.PathMapSizeRequest;
import io.prometheus.grpc.PathMapSizeResponse;
import io.prometheus.grpc.ProxyServiceGrpc;
import io.prometheus.grpc.RegisterAgentRequest;
import io.prometheus.grpc.RegisterAgentResponse;
import io.prometheus.grpc.RegisterPathRequest;
import io.prometheus.grpc.RegisterPathResponse;
import io.prometheus.grpc.ScrapeRequest;
import io.prometheus.grpc.ScrapeResponse;
import io.prometheus.grpc.UnregisterPathRequest;
import io.prometheus.grpc.UnregisterPathResponse;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static io.grpc.ClientInterceptors.intercept;
import static io.prometheus.common.InstrumentedThreadFactory.newInstrumentedThreadFactory;
import static io.prometheus.common.Utils.sleepForMillis;
import static io.prometheus.common.Utils.toMillis;
import static io.prometheus.grpc.ProxyServiceGrpc.newBlockingStub;
import static io.prometheus.grpc.ProxyServiceGrpc.newStub;
import static java.lang.String.format;
import static java.util.concurrent.Executors.newCachedThreadPool;

public class Agent
    extends AbstractExecutionThreadService
    implements Closeable {

  private static final Logger logger = LoggerFactory.getLogger(Agent.class);

  private final AtomicBoolean            stopped                = new AtomicBoolean(false);
  private final Map<String, PathContext> pathContextMap         = Maps.newConcurrentMap();  // Map path to PathContext
  private final AtomicReference<String>  agentIdRef             = new AtomicReference<>();
  private final AtomicLong               lastMsgSent            = new AtomicLong();
  private final ExecutorService          heartbeatService       = Executors.newFixedThreadPool(1);
  private final CountDownLatch           initialConnectionLatch = new CountDownLatch(1);
  private final OkHttpClient             okHttpClient           = new OkHttpClient();

  private final ConfigVals                    configVals;
  private final String                        inProcessServerName;
  private final String                        agentName;
  private final String                        hostname;
  private final int                           port;
  private final AgentMetrics                  metrics;
  private final ExecutorService               executorService;
  private final BlockingQueue<ScrapeResponse> scrapeResponseQueue;
  private final RateLimiter                   reconnectLimiter;
  private final MetricsServer                 metricsServer;
  private final List<Map<String, String>>     pathConfigs;
  private final ZipkinReporter                zipkinReporter;
  private final boolean                       testMode;

  private ManagedChannel                            channel      = null;
  private ProxyServiceGrpc.ProxyServiceBlockingStub blockingStub = null;
  private ProxyServiceGrpc.ProxyServiceStub         asyncStub    = null;

  public Agent(final ConfigVals configVals,
               final String inProcessServerName,
               final String agentName,
               final String proxyHost,
               final boolean metricsEnabled,
               final int metricsPort,
               final boolean testMode) {
    this.configVals = configVals;
    this.inProcessServerName = inProcessServerName;
    this.testMode = testMode;
    this.agentName = isNullOrEmpty(agentName) ? format("Unnamed-%s", Utils.getHostName()) : agentName;
    logger.info("Creating Agent {}", this.agentName);

    final int queueSize = this.getConfigVals().internal.scrapeResponseQueueSize;
    this.scrapeResponseQueue = new ArrayBlockingQueue<>(queueSize);

    if (metricsEnabled) {
      logger.info("Metrics server enabled with {} /{}", metricsPort, this.getConfigVals().metrics.path);
      this.metricsServer = new MetricsServer(metricsPort, this.getConfigVals().metrics.path);
      this.metrics = new AgentMetrics(this);
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

    this.executorService = newCachedThreadPool(this.isMetricsEnabled()
                                               ? newInstrumentedThreadFactory("agent_fetch",
                                                                              "Agent fetch",
                                                                              true)
                                               : new ThreadFactoryBuilder().setNameFormat("agent_fetch-%d")
                                                                           .setDaemon(true)
                                                                           .build());

    logger.info("Assigning proxy reconnect pause time to {} secs", this.getConfigVals().internal.reconectPauseSecs);
    this.reconnectLimiter = RateLimiter.create(1.0 / this.getConfigVals().internal.reconectPauseSecs);

    this.pathConfigs = configVals.agent.pathConfigs.stream()
                                                   .map(v -> ImmutableMap.of("name", v.name,
                                                                             "path", v.path,
                                                                             "url", v.url))
                                                   .peek(v -> logger.info("Proxy path /{} will be assigned to {}",
                                                                          v.get("path"), v.get("url")))
                                                   .collect(Collectors.toList());

    if (this.getConfigVals().internal.zipkin.enabled) {
      final ConfigVals.Agent.Internal.Zipkin zipkin = this.getConfigVals().internal.zipkin;
      final String zipkinHost = format("http://%s:%d/%s", zipkin.hostname, zipkin.port, zipkin.path);
      logger.info("Zipkin reporter enabled for {}", zipkinHost);
      this.zipkinReporter = new ZipkinReporter(zipkinHost, zipkin.serviceName);
    }
    else {
      logger.info("Zipkin reporter disabled");
      this.zipkinReporter = null;
    }

    if (proxyHost.contains(":")) {
      String[] vals = proxyHost.split(":");
      this.hostname = vals[0];
      this.port = Integer.valueOf(vals[1]);
    }
    else {
      this.hostname = proxyHost;
      this.port = 50051;
    }
  }

  public static void main(final String[] argv)
      throws IOException, InterruptedException {

    final AgentOptions options = new AgentOptions(Agent.class.getName(), argv, true);

    logger.info(Utils.getBanner("banners/agent.txt"));
    logger.info(Utils.getVersionDesc());

    final Agent agent = new Agent(options.getConfigVals(),
                                  null,
                                  options.getAgentName(),
                                  options.getProxyHostname(),
                                  options.getEnableMetrics(),
                                  options.getMetricsPort(),
                                  false);
    agent.addListener(new GenericServiceListener(agent), MoreExecutors.directExecutor());
    agent.startAsync();
  }

  @Override
  protected void startUp()
      throws Exception {
    super.startUp();

    if (this.isMetricsEnabled())
      this.metricsServer.start();

    // Prime the limiter
    this.reconnectLimiter.acquire();

    Runtime.getRuntime()
           .addShutdownHook(
               new Thread(() -> {
                 JCommander.getConsole().println("*** Shutting down Agent ***");
                 Agent.this.stopAsync();
                 JCommander.getConsole().println("*** Agent shut down ***");
               }));

    this.resetGrpcStubs();
  }

  @Override
  protected void shutDown()
      throws Exception {

    if (this.isMetricsEnabled())
      this.metricsServer.stop();

    if (this.isZipkinReportingEnabled())
      this.zipkinReporter.close();

    this.heartbeatService.shutdownNow();

    this.channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);

    super.shutDown();
  }

  @Override
  protected void run() {
    while (this.isRunning()) {
      try {
        this.connectToProxy();
      }
      catch (RequestFailureException e) {
        logger.info("Disconnected from proxy at {} after invalid response {}",
                    this.getProxyHost(), e.getMessage());
      }
      catch (StatusRuntimeException e) {
        logger.info("Disconnected from proxy at {}", this.getProxyHost());
      }
      catch (Exception e) {
        // Catch anything else to avoid exiting retry loop
        logger.info("Disconnected from proxy at {} - {} [{}]",
                    this.getProxyHost(), e.getClass().getSimpleName(), e.getMessage());
      }
      finally {
        final double secsWaiting = this.reconnectLimiter.acquire();
        logger.info("Waited {} secs to reconnect", secsWaiting);
      }
    }
  }

  @Override
  protected String serviceName() {
    return format("%s %s", this.getClass().getSimpleName(), this.agentName);
  }

  private void connectToProxy()
      throws RequestFailureException {
    final AtomicBoolean disconnected = new AtomicBoolean(false);

    // Reset gRPC stubs if previous iteration had a successful connection, i.e., the agent id != null
    if (this.getAgentId() != null)
      this.resetGrpcStubs();

    // Reset values for each connection attempt
    this.setAgentId(null);
    this.pathContextMap.clear();
    this.scrapeResponseQueue.clear();
    this.lastMsgSent.set(0);

    if (this.connectAgent()) {
      this.registerAgent();
      this.registerPaths();
      this.startHeartBeat(disconnected);
      this.readRequestsFromProxy(disconnected);
      this.writeResponsesToProxyUntilDisconnected(disconnected);
    }
  }

  @Override
  public void close()
      throws IOException {
    this.stopAsync();
  }

  private void startHeartBeat(final AtomicBoolean disconnected) {
    if (this.getConfigVals().internal.heartbeatEnabled) {
      final long threadPauseMillis = this.getConfigVals().internal.heartbeatCheckPauseMillis;
      final int maxInactivitySecs = this.getConfigVals().internal.heartbeatMaxInactivitySecs;
      logger.info("Heartbeat scheduled to fire after {} secs of inactivity", maxInactivitySecs);
      this.heartbeatService.submit(
          () -> {
            while (!disconnected.get()) {
              final long timeSinceLastWriteMillis = System.currentTimeMillis() - this.lastMsgSent.get();
              if (timeSinceLastWriteMillis > toMillis(maxInactivitySecs))
                this.sendHeartBeat(disconnected);
              sleepForMillis(threadPauseMillis);
            }
            logger.info("Heartbeat completed");
          });
    }
    else {
      logger.info("Heartbeat disabled");
    }
  }

  private void resetGrpcStubs() {
    logger.info("Assigning gRPC stubs");
    this.channel = isNullOrEmpty(this.inProcessServerName) ? NettyChannelBuilder.forAddress(this.hostname, this.port)
                                                                                .usePlaintext(true)
                                                                                .build()
                                                           : InProcessChannelBuilder.forName(this.inProcessServerName)
                                                                                    .usePlaintext(true)
                                                                                    .build();

    final List<ClientInterceptor> interceptors = Lists.newArrayList(new AgentClientInterceptor(this));

    /*
    if (this.getConfigVals().metrics.grpc.metricsEnabled)
      interceptors.add(MonitoringClientInterceptor.create(this.getConfigVals().grpc.allMetricsReported
                                                          ? Configuration.allMetrics()
                                                          : Configuration.cheapMetricsOnly()));
    if (this.zipkinReporter != null && this.getConfigVals().grpc.zipkinReportingEnabled)
      interceptors.add(BraveGrpcClientInterceptor.create(this.zipkinReporter.getBrave()));
    */
    this.blockingStub = newBlockingStub(intercept(this.channel, interceptors));
    this.asyncStub = newStub(intercept(this.channel, interceptors));
  }

  private void updateScrapeCounter(final String type) {
    if (this.isMetricsEnabled())
      this.getMetrics().scrapeRequests.labels(type).inc();
  }

  private ScrapeResponse fetchUrl(final ScrapeRequest scrapeRequest) {
    int statusCode = 404;
    final String path = scrapeRequest.getPath();
    final ScrapeResponse.Builder scrapeResponse = ScrapeResponse.newBuilder()
                                                                .setAgentId(scrapeRequest.getAgentId())
                                                                .setScrapeId(scrapeRequest.getScrapeId());
    final PathContext pathContext = this.pathContextMap.get(path);
    if (pathContext == null) {
      logger.warn("Invalid path in fetchUrl(): {}", path);
      this.updateScrapeCounter("invalid_path");
      return scrapeResponse.setValid(false)
                           .setReason(format("Invalid path: %s", path))
                           .setStatusCode(statusCode)
                           .setText("")
                           .setContentType("")
                           .build();
    }

    final Summary.Timer requestTimer = this.isMetricsEnabled()
                                       ? this.getMetrics().scrapeRequestLatency.labels(this.agentName).startTimer()
                                       : null;
    String reason = "None";
    try {
      try (final Response response = pathContext.fetchUrl(scrapeRequest)) {
        statusCode = response.code();
        if (response.isSuccessful()) {
          this.updateScrapeCounter("success");
          return scrapeResponse.setValid(true)
                               .setReason("")
                               .setStatusCode(statusCode)
                               .setText(response.body().string())
                               .setContentType(response.header(CONTENT_TYPE))
                               .build();
        }
        else {
          reason = format("Unsucessful response code %d", statusCode);
        }
      }
    }
    catch (IOException e) {
      reason = format("%s - %s", e.getClass().getSimpleName(), e.getMessage());
    }
    catch (Exception e) {
      logger.warn("fetchUrl()", e);
      reason = format("%s - %s", e.getClass().getSimpleName(), e.getMessage());
    }
    finally {
      if (requestTimer != null)
        requestTimer.observeDuration();
    }

    this.updateScrapeCounter("unsuccessful");

    return scrapeResponse.setValid(false)
                         .setReason(reason)
                         .setStatusCode(statusCode)
                         .setText("")
                         .setContentType("")
                         .build();
  }

  // If successful, this will create an agentContxt on the Proxy and an interceptor will
  // add an agent_id to the headers
  private boolean connectAgent() {
    try {
      logger.info("Connecting to proxy at {}...", this.getProxyHost());
      this.blockingStub.connectAgent(Empty.getDefaultInstance());
      logger.info("Connected to proxy at {}", this.getProxyHost());
      if (this.isMetricsEnabled())
        this.getMetrics().connects.labels("success").inc();
      return true;
    }
    catch (StatusRuntimeException e) {
      if (this.isMetricsEnabled())
        this.getMetrics().connects.labels("failure").inc();
      logger.info("Cannot connect to proxy at {} [{}]", this.getProxyHost(), e.getMessage());
      return false;
    }
  }

  private void registerAgent()
      throws RequestFailureException {
    final RegisterAgentRequest request = RegisterAgentRequest.newBuilder()
                                                             .setAgentId(this.getAgentId())
                                                             .setAgentName(this.agentName)
                                                             .setHostname(Utils.getHostName())
                                                             .build();
    final RegisterAgentResponse response = this.blockingStub.registerAgent(request);
    this.markMsgSent();
    if (!response.getValid())
      throw new RequestFailureException(format("registerAgent() - %s", response.getReason()));

    this.initialConnectionLatch.countDown();
  }

  private void registerPaths()
      throws RequestFailureException {
    for (final Map<String, String> agentConfig : this.pathConfigs) {
      final String path = agentConfig.get("path");
      final String url = agentConfig.get("url");
      this.registerPath(path, url);
    }
  }

  public void registerPath(final String pathVal, final String url)
      throws RequestFailureException {
    final String path = checkNotNull(pathVal).startsWith("/") ? pathVal.substring(1) : pathVal;
    final long pathId = this.registerPathOnProxy(path);
    if (!this.testMode)
      logger.info("Registered {} as /{}", url, path);
    this.pathContextMap.put(path, new PathContext(this.okHttpClient, pathId, path, url));
  }

  public void unregisterPath(final String pathVal)
      throws RequestFailureException {
    final String path = checkNotNull(pathVal).startsWith("/") ? pathVal.substring(1) : pathVal;
    this.unregisterPathOnProxy(path);
    final PathContext pathContext = this.pathContextMap.remove(path);
    if (pathContext == null)
      logger.info("No path value /{} found in pathContextMap", path);
    else if (!this.testMode)
      logger.info("Unregistered /{} for {}", path, pathContext.getUrl());
  }

  public int pathMapSize() {
    final PathMapSizeRequest request = PathMapSizeRequest.newBuilder()
                                                         .setAgentId(this.getAgentId())
                                                         .build();
    final PathMapSizeResponse response = this.blockingStub.pathMapSize(request);
    this.markMsgSent();
    return response.getPathCount();
  }

  private long registerPathOnProxy(final String path)
      throws RequestFailureException {
    final RegisterPathRequest request = RegisterPathRequest.newBuilder()
                                                           .setAgentId(this.getAgentId())
                                                           .setPath(path)
                                                           .build();
    final RegisterPathResponse response = this.blockingStub.registerPath(request);
    this.markMsgSent();
    if (!response.getValid())
      throw new RequestFailureException(format("registerPath() - %s", response.getReason()));
    return response.getPathId();
  }

  private void unregisterPathOnProxy(final String path)
      throws RequestFailureException {
    final UnregisterPathRequest request = UnregisterPathRequest.newBuilder()
                                                               .setAgentId(this.getAgentId())
                                                               .setPath(path)
                                                               .build();
    final UnregisterPathResponse response = this.blockingStub.unregisterPath(request);
    this.markMsgSent();
    if (!response.getValid())
      throw new RequestFailureException(format("unregisterPath() - %s", response.getReason()));
  }

  private void readRequestsFromProxy(final AtomicBoolean disconnected) {
    final StreamObserver<ScrapeRequest> streamObserver =
        new StreamObserver<ScrapeRequest>() {
          @Override
          public void onNext(final ScrapeRequest request) {
            executorService.submit(
                () -> {
                  final ScrapeResponse response = fetchUrl(request);
                  try {
                    scrapeResponseQueue.put(response);
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
        };
    this.asyncStub.readRequestsFromProxy(AgentInfo.newBuilder().setAgentId(this.getAgentId()).build(), streamObserver);
  }

  private void writeResponsesToProxyUntilDisconnected(final AtomicBoolean disconnected) {
    final StreamObserver<ScrapeResponse> responseObserver = this.asyncStub.writeResponsesToProxy(
        new StreamObserver<Empty>() {
          @Override
          public void onNext(Empty empty) {
            // Ignore Empty return value
          }

          @Override
          public void onError(Throwable t) {
            final Status s = Status.fromThrowable(t);
            logger.info("Error in writeResponsesToProxyUntilDisconnected(): {} {}", s.getCode(), s.getDescription());
            disconnected.set(true);
          }

          @Override
          public void onCompleted() {
            disconnected.set(true);
          }
        });

    final long checkMillis = this.getConfigVals().internal.scrapeResponseQueueCheckMillis;
    while (!disconnected.get()) {
      try {
        // Set a short timeout to check if client has disconnected
        final ScrapeResponse response = this.scrapeResponseQueue.poll(checkMillis, TimeUnit.MILLISECONDS);
        if (response != null) {
          responseObserver.onNext(response);
          this.markMsgSent();
        }
      }
      catch (InterruptedException e) {
        // Ignore
      }
    }

    logger.info("Disconnected from proxy at {}", this.getProxyHost());

    responseObserver.onCompleted();
  }

  private void markMsgSent() {
    this.lastMsgSent.set(System.currentTimeMillis());
  }

  private void sendHeartBeat(final AtomicBoolean disconnected) {
    final String agentId = this.getAgentId();
    if (agentId == null)
      return;
    try {
      final HeartBeatRequest request = HeartBeatRequest.newBuilder().setAgentId(agentId).build();
      final HeartBeatResponse response = this.blockingStub.sendHeartBeat(request);
      this.markMsgSent();
      if (!response.getValid()) {
        logger.info("AgentId {} not found on proxy", agentId);
        throw new StatusRuntimeException(Status.NOT_FOUND);
      }
    }
    catch (StatusRuntimeException e) {
      logger.info("Hearbeat failed {}", e.getStatus());
      disconnected.set(true);
    }
  }

  public boolean awaitInitialConnection(long timeout, TimeUnit unit)
      throws InterruptedException {
    return this.initialConnectionLatch.await(timeout, unit);
  }

  private String getProxyHost() { return format("%s:%s", hostname, port); }

  public int getScrapeResponseQueueSize() { return this.scrapeResponseQueue.size(); }

  public AgentMetrics getMetrics() { return this.metrics; }

  public boolean isMetricsEnabled() { return this.metricsServer != null; }

  public boolean isZipkinReportingEnabled() { return this.zipkinReporter != null; }

  public ManagedChannel getChannel() { return this.channel; }

  public String getAgentId() { return this.agentIdRef.get(); }

  public void setAgentId(final String agentId) { this.agentIdRef.set(agentId); }

  public ConfigVals.Agent getConfigVals() { return this.configVals.agent; }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("agentId", this.getAgentId())
                      .add("agentName", this.agentName)
                      .add("metricsPort", this.isMetricsEnabled() ? this.metricsServer.getPort() : "Disabled")
                      .add("metricsPath", this.isMetricsEnabled() ? "/" + this.metricsServer.getPath() : "Disabled")
                      .add("proxyHost", this.getProxyHost())
                      .toString();
  }
}
