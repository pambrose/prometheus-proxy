package com.sudothought.agent;

import com.google.common.collect.Maps;
import com.google.protobuf.Empty;
import com.sudothought.grpc.AgentInfo;
import com.sudothought.grpc.ProxyServiceGrpc;
import com.sudothought.grpc.RegisterAgentRequest;
import com.sudothought.grpc.RegisterAgentResponse;
import com.sudothought.grpc.RegisterPathRequest;
import com.sudothought.grpc.RegisterPathResponse;
import com.sudothought.grpc.ScrapeRequest;
import com.sudothought.grpc.ScrapeResponse;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.net.HttpHeaders.CONTENT_TYPE;

public class Agent {

  private static final Logger logger        = LoggerFactory.getLogger(Agent.class);
  private static final String AGENT_CONFIGS = "agent_configs";

  private final ExecutorService               executorService     = Executors.newFixedThreadPool(2);
  private final BlockingQueue<ScrapeResponse> scrapeResponseQueue = new ArrayBlockingQueue<>(1000);

  // Map path to PathContext
  private final Map<String, PathContext> pathContextMap = Maps.newConcurrentMap();
  private final AtomicReference<String>  agentIdRef     = new AtomicReference<>();
  private final AtomicBoolean            stopped        = new AtomicBoolean(false);

  private final String                                    hostname;
  private final List<Map<String, String>>                 agentConfigs;
  private final ManagedChannel                            channel;
  private final ProxyServiceGrpc.ProxyServiceBlockingStub blockingStub;
  private final ProxyServiceGrpc.ProxyServiceStub         asyncStub;

  private Agent(String hostname, final List<Map<String, String>> agentConfigs) {
    this.agentConfigs = agentConfigs;

    final String host;
    final int port;
    if (hostname.contains(":")) {
      String[] vals = hostname.split(":");
      host = vals[0];
      port = Integer.valueOf(vals[1]);
    }
    else {
      host = hostname;
      port = 50051;
    }
    this.hostname = String.format("%s:%s", host, port);
    final ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(host, port)
                                                                         .usePlaintext(true);
    this.channel = channelBuilder.build();

    final ClientInterceptor interceptor = new AgentClientInterceptor(this);

    this.blockingStub = ProxyServiceGrpc.newBlockingStub(ClientInterceptors.intercept(this.channel, interceptor));
    this.asyncStub = ProxyServiceGrpc.newStub(ClientInterceptors.intercept(this.channel, interceptor));
  }

  public static void main(final String[] argv) {
    final AgentArgs agentArgs = new AgentArgs();
    agentArgs.parseArgs(Agent.class.getName(), argv);

    final List<Map<String, String>> agentConfigs;
    try {
      agentConfigs = readAgentConfigs(agentArgs.config);
    }
    catch (IOException e) {
      logger.error(e.getMessage());
      return;
    }

    final Agent agent = new Agent(agentArgs.proxy_hostname, agentConfigs);
    agent.run();
  }

  private static List<Map<String, String>> readAgentConfigs(final String filename)
      throws IOException {
    logger.info("Loading configuration file {}", filename);
    final Yaml yaml = new Yaml();
    try (final InputStream input = new FileInputStream(new File(filename))) {
      final Map<String, List<Map<String, String>>> data = (Map<String, List<Map<String, String>>>) yaml.load(input);
      if (!data.containsKey(AGENT_CONFIGS))
        throw new IOException(String.format("Missing %s key in config file %s", AGENT_CONFIGS, filename));
      return data.get(AGENT_CONFIGS);
    }
    catch (FileNotFoundException e) {
      throw new IOException(String.format("Config file not found: %s", filename));
    }
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

  private void run() {
    Runtime.getRuntime()
           .addShutdownHook(
               new Thread(() -> {
                 System.err.println("*** Shutting down Agent since JVM is shutting down");
                 Agent.this.stop();
                 System.err.println("*** Agent shut down");
               }));

    while (!this.isStopped()) {
      boolean connected = false;
      this.setAgentId(null);
      this.pathContextMap.clear();
      this.scrapeResponseQueue.clear();

      try {
        logger.info("Connecting to proxy at {}...", this.hostname);
        this.connectAgent();
        logger.info("Connected to proxy at {}", this.hostname);
        connected = true;

        this.registerAgent();
        this.registerPaths();

        final CountDownLatch countDownLatch = new CountDownLatch(2);

        this.executorService.submit(
            () -> {
              final AgentInfo agentInfo = AgentInfo.newBuilder()
                                                   .setAgentId(this.getAgenId())
                                                   .build();
              this.asyncStub.readRequestsFromProxy(
                  agentInfo,
                  new StreamObserver<ScrapeRequest>() {
                    @Override
                    public void onNext(final ScrapeRequest scrapeRequest) {
                      final ScrapeResponse scrapeResponse = fetchScrapeResponse(scrapeRequest);
                      try {
                        scrapeResponseQueue.put(scrapeResponse);
                      }
                      catch (InterruptedException e) {
                        // Ignore
                      }
                    }

                    @Override
                    public void onError(Throwable t) {
                      final Status status = Status.fromThrowable(t);
                      logger.info("onError() in readRequestsFromProxy(): {}", status);
                      countDownLatch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                      countDownLatch.countDown();
                    }
                  });
            });

        this.executorService.submit(
            () -> {
              final StreamObserver<ScrapeResponse> responseObserver =
                  this.asyncStub.writeResponsesToProxy(new StreamObserver<Empty>() {
                    @Override
                    public void onNext(Empty rmpty) {
                      // Ignore
                    }

                    @Override
                    public void onError(Throwable t) {
                      final Status status = Status.fromThrowable(t);
                      logger.info("onError() in writeResponsesToProxy(): {}", status);
                      countDownLatch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                      countDownLatch.countDown();
                    }
                  });

              while (countDownLatch.getCount() == 2) {
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

              responseObserver.onCompleted();
            });

        // Wait for both threads to finish
        countDownLatch.await();
      }
      catch (ConnectException | InterruptedException e) {
        // Ignore
      }
      catch (StatusRuntimeException e) {
        logger.info("Cannot connect to proxy at {} [{}]", this.hostname, e.getMessage());
      }

      if (connected)
        logger.info("Disconnected from proxy at {}", this.hostname);

      try {
        Thread.sleep(2000);
      }
      catch (InterruptedException e) {
        // Ignore
      }
    }
  }

  private ScrapeResponse invalidScrapeResponse(final ScrapeRequest scrapeRequest) {
    return ScrapeResponse.newBuilder()
                         .setAgentId(scrapeRequest.getAgentId())
                         .setScrapeId(scrapeRequest.getScrapeId())
                         .setValid(false)
                         .setStatusCode(404)
                         .setText("")
                         .setContentType("")
                         .build();
  }

  private ScrapeResponse fetchScrapeResponse(final ScrapeRequest scrapeRequest) {
    final String path = scrapeRequest.getPath();
    final PathContext pathContext = this.pathContextMap.get(path);
    ScrapeResponse scrapeResponse;
    if (pathContext == null) {
      logger.warn("Invalid path request: {}", path);
      scrapeResponse = this.invalidScrapeResponse(scrapeRequest);
    }
    else {
      try {
        logger.info("Fetching path request /{} {}", path, pathContext.getUrl());
        final Response res = pathContext.fetchUrl(scrapeRequest);
        scrapeResponse = ScrapeResponse.newBuilder()
                                       .setAgentId(scrapeRequest.getAgentId())
                                       .setScrapeId(scrapeRequest.getScrapeId())
                                       .setValid(true)
                                       .setStatusCode(res.code())
                                       .setText(res.body().string())
                                       .setContentType(res.header(CONTENT_TYPE))
                                       .build();
      }
      catch (IOException e) {
        scrapeResponse = this.invalidScrapeResponse(scrapeRequest);
      }
    }
    return scrapeResponse;
  }

  public void stop() {
    this.stopped.set(true);

    try {
      this.channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
    }
    catch (InterruptedException e) {
      // Ignore
    }

    this.executorService.shutdownNow();

    try {
      this.executorService.awaitTermination(1, TimeUnit.SECONDS);
    }
    catch (InterruptedException e) {
      // Ignore
    }
  }

  private void connectAgent() { this.blockingStub.connectAgent(Empty.getDefaultInstance()); }

  private void registerAgent()
      throws ConnectException {
    final RegisterAgentRequest request = RegisterAgentRequest.newBuilder()
                                                             .setAgentId(this.getAgenId())
                                                             .setHostname(getHostName())
                                                             .build();
    final RegisterAgentResponse response = this.blockingStub.registerAgent(request);
    if (!response.getValid())
      throw new ConnectException("registerAgent()");
  }

  private void registerPaths()
      throws ConnectException {
    for (Map<String, String> agentConfig : this.agentConfigs) {
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
                                                           .setAgentId(this.getAgenId())
                                                           .setPath(path)
                                                           .build();
    final RegisterPathResponse response = this.blockingStub.registerPath(request);
    if (!response.getValid())
      throw new ConnectException("registerPath()");
    return response.getPathId();
  }

  public ManagedChannel getChannel() { return this.channel; }

  public void setAgentId(final String agentId) { this.agentIdRef.set(agentId); }

  private String getAgenId() { return this.agentIdRef.get(); }

  private boolean isStopped() { return this.stopped.get(); }
}
