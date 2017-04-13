package com.sudothought;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Maps;
import com.google.protobuf.Empty;
import com.sudothought.args.AgentArgs;
import com.sudothought.grpc.AgentInfo;
import com.sudothought.grpc.ProxyServiceGrpc;
import com.sudothought.grpc.RegisterAgentRequest;
import com.sudothought.grpc.RegisterAgentResponse;
import com.sudothought.grpc.RegisterPathRequest;
import com.sudothought.grpc.RegisterPathResponse;
import com.sudothought.grpc.ScrapeRequest;
import com.sudothought.grpc.ScrapeResponse;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
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

import static com.sudothought.Proxy.AGENT_ID;

public class Agent {

  private static final Logger logger = LoggerFactory.getLogger(Agent.class);

  private final BlockingQueue<ScrapeResponse> scrapeResponseQueue = new ArrayBlockingQueue<>(1000);

  // Map path to PathContext
  private final Map<String, PathContext> pathContextMap = Maps.newConcurrentMap();
  private final AtomicBoolean            stopped        = new AtomicBoolean(false);
  private final AtomicReference<String>  agentIdRef     = new AtomicReference();

  private final String                                    hostname;
  private final List<Map<String, String>>                 agentConfigs;
  private final ManagedChannel                            channel;
  private final ProxyServiceGrpc.ProxyServiceBlockingStub blockingStub;
  private final ProxyServiceGrpc.ProxyServiceStub         asyncStub;

  public Agent(String hostname, final List<Map<String, String>> agentConfigs) {
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
    final ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(host, port).usePlaintext(true);
    this.channel = channelBuilder.build();

    final ClientInterceptor interceptor = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(final MethodDescriptor<ReqT, RespT> method,
                                                                 final CallOptions callOptions,
                                                                 final Channel next) {
        final String methodName = method.getFullMethodName();
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(channel.newCall(method, callOptions)) {
          @Override
          public void start(final Listener<RespT> responseListener, final Metadata headers) {
            final Stopwatch stopwatch = Stopwatch.createStarted();
            super.start(
                new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                  @Override
                  public void onHeaders(Metadata headers) {
                    String agent_id = headers.get(Metadata.Key.of(AGENT_ID, Metadata.ASCII_STRING_MARSHALLER));
                    agentIdRef.set(agent_id);
                    super.onHeaders(headers);
                  }

                  @Override
                  public void onMessage(RespT message) {
                    super.onMessage(message);
                  }

                  @Override
                  public void onClose(Status status, Metadata trailers) {
                    super.onClose(status, trailers);
                  }

                  @Override
                  public void onReady() {
                    super.onReady();
                  }
                },
                headers);
          }
        };
      }
    };

    this.blockingStub = ProxyServiceGrpc.newBlockingStub(ClientInterceptors.intercept(this.channel, interceptor));
    this.asyncStub = ProxyServiceGrpc.newStub(ClientInterceptors.intercept(this.channel, interceptor));
  }

  public static void main(final String[] argv)
      throws Exception {

    final AgentArgs agentArgs = new AgentArgs();
    agentArgs.parseArgs(Agent.class.getName(), argv);

    final List<Map<String, String>> agentConfigs;
    try {
      agentConfigs = readAgentConfigs(agentArgs.config);
    }
    catch (FileNotFoundException e) {
      logger.warn("Invalid config file name: {}", agentArgs.config);
      return;
    }

    final Agent agent = new Agent(agentArgs.proxy, agentConfigs);
    agent.connect(true);

    agent.shutdown();
  }

  private static List<Map<String, String>> readAgentConfigs(final String filename)
      throws FileNotFoundException {
    final Yaml yaml = new Yaml();
    final InputStream input = new FileInputStream(new File(filename));
    final Map<String, List<Map<String, String>>> data = (Map<String, List<Map<String, String>>>) yaml.load(input);
    return data.get("agent_configs");
  }

  public void connect(final boolean reconnect)
      throws InterruptedException {

    final ExecutorService executorService = Executors.newFixedThreadPool(2);

    while (true) {
      try {
        logger.info("Connecting to proxy at {}...", this.hostname);
        this.connectAgent();
        logger.info("Connected to proxy at {}", this.hostname);

        this.registerAgent();
        this.registerPaths();

        final CountDownLatch countDownLatch = new CountDownLatch(1);

        executorService.submit(() -> {
          final AgentInfo agentInfo = AgentInfo.newBuilder().setAgentId(agentIdRef.get()).build();
          this.asyncStub.readRequestsFromProxy(
              agentInfo,
              new StreamObserver<ScrapeRequest>() {
                @Override
                public void onNext(ScrapeRequest scrapeRequest) {
                  final PathContext pathContext = pathContextMap.get(scrapeRequest.getPath());
                  ScrapeResponse scrapResponse;
                  try {
                    final Response response = pathContext.fetchUrl();
                    scrapResponse = ScrapeResponse.newBuilder()
                                                  .setAgentId(scrapeRequest.getAgentId())
                                                  .setScrapeId(scrapeRequest.getScrapeId())
                                                  .setValid(true)
                                                  .setStatusCode(response.code())
                                                  .setText(response.body().string())
                                                  .build();
                  }
                  catch (IOException e) {
                    scrapResponse = ScrapeResponse.newBuilder()
                                                  .setAgentId(scrapeRequest.getAgentId())
                                                  .setScrapeId(scrapeRequest.getScrapeId())
                                                  .setValid(false)
                                                  .setStatusCode(404)
                                                  .setText("")
                                                  .build();
                    e.printStackTrace();
                  }

                  try {
                    scrapeResponseQueue.put(scrapResponse);
                  }
                  catch (InterruptedException e) {
                    e.printStackTrace();
                  }
                }

                @Override
                public void onError(Throwable t) {
                  final Status status = Status.fromThrowable(t);
                  logger.warn("Failed: {}", status);
                  countDownLatch.countDown();
                }

                @Override
                public void onCompleted() {
                  logger.info("Completed");
                }
              });
        });

        executorService.submit(() -> {
          while (countDownLatch.getCount() > 0) {
            try {
              final ScrapeResponse response = this.scrapeResponseQueue.poll(1, TimeUnit.SECONDS);
              if (response == null)
                continue;
              this.blockingStub.writeResponseToProxy(response);
            }
            catch (InterruptedException e) {
              e.printStackTrace();
            }
          }
          logger.info("Exiting");
        });

        countDownLatch.await();
        logger.info("Disconnected from proxy at {}", this.hostname);
      }
      catch (ReconnectException e) {
        logger.info("Reconnecting on ReconnectException: {}", e.getMessage());
      }
      catch (StatusRuntimeException e) {
        logger.info("Cannot connect to proxy at {} [{}]", this.hostname, e.getMessage());
      }

      if (!reconnect)
        break;

      Thread.sleep(2000);
    }
  }

  public void shutdown()
      throws InterruptedException {
    this.channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  public void connectAgent() {
    this.blockingStub.connectAgent(Empty.getDefaultInstance());
  }

  public void registerAgent()
      throws ReconnectException {
    final RegisterAgentRequest request = RegisterAgentRequest.newBuilder()
                                                             .setAgentId(this.agentIdRef.get())
                                                             .setHostname(Utils.getHostName())
                                                             .build();
    final RegisterAgentResponse response = this.blockingStub.registerAgent(request);
    if (!response.getValid())
      throw new ReconnectException("registerAgent()");
  }

  public void registerPaths()
      throws ReconnectException {
    for (Map<String, String> agentConfig : this.agentConfigs) {
      final String path = agentConfig.get("path");
      final String url = agentConfig.get("url");
      final long pathId = this.registerPath(path);
      logger.info("Registered {} as /{}", url, path);
      this.pathContextMap.put(path, new PathContext(pathId, path, url));
    }
  }

  public long registerPath(final String path)
      throws ReconnectException {
    final RegisterPathRequest request = RegisterPathRequest.newBuilder()
                                                           .setAgentId(this.agentIdRef.get())
                                                           .setPath(path)
                                                           .build();
    final RegisterPathResponse response = this.blockingStub.registerPath(request);
    if (!response.getValid())
      throw new ReconnectException("registerPath()");
    return response.getPathId();
  }

}
