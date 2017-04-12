package com.sudothought;

import com.cinch.grpc.AgentInfo;
import com.cinch.grpc.ProxyServiceGrpc;
import com.cinch.grpc.RegisterAgentRequest;
import com.cinch.grpc.RegisterAgentResponse;
import com.cinch.grpc.RegisterPathRequest;
import com.cinch.grpc.RegisterPathResponse;
import com.cinch.grpc.ScrapeRequest;
import com.cinch.grpc.ScrapeResponse;
import com.google.common.collect.Maps;
import com.sudothought.args.AgentArgs;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import okhttp3.Response;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Agent {

  private static final Logger logger = Logger.getLogger(Agent.class.getName());

  private final BlockingQueue<ScrapeResponse> scrapeResponseQueue = new ArrayBlockingQueue<>(1000);

  // Map path to PathContext
  private final Map<String, PathContext> pathContextMap = Maps.newConcurrentMap();
  private final AtomicBoolean            stopped        = new AtomicBoolean(false);
  private final AtomicLong               agentIdRef     = new AtomicLong();

  private final String                                    hostname;
  private final List<Map<String, String>>                 agentConfigs;
  private final ManagedChannel                            channel;
  private final ProxyServiceGrpc.ProxyServiceBlockingStub blockingStub;
  private final ProxyServiceGrpc.ProxyServiceStub         asyncStub;

  public Agent(String hostname, final List<Map<String, String>> agentConfigs) {
    final String host;
    final int port;
    if (hostname.contains(":")) {
      String[] vals = hostname.split(":");
      host = vals[0];
      port = Integer.getInteger(vals[1]);
    }
    else {
      host = hostname;
      port = 50051;
    }
    this.hostname = String.format("%s:%s", host, port);
    final ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(host, port).usePlaintext(true);
    this.agentConfigs = agentConfigs;
    this.channel = channelBuilder.build();
    this.blockingStub = ProxyServiceGrpc.newBlockingStub(this.channel);
    this.asyncStub = ProxyServiceGrpc.newStub(this.channel);
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
      logger.log(Level.WARNING, String.format("Invalid config file name: %s", agentArgs.config));
      return;
    }

    final Agent agent = new Agent(agentArgs.proxy, agentConfigs);
    agent.connect(true);
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
        logger.log(Level.INFO, String.format("Connecting to proxy at %s...", this.hostname));
        this.registerAgent();
        logger.log(Level.INFO, String.format("Connected to proxy at %s", this.hostname));

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
                  logger.log(Level.WARNING, "Failed: {0}", status);
                  countDownLatch.countDown();
                }

                @Override
                public void onCompleted() {
                  logger.log(Level.INFO, "Completed");
                }
              });
        });

        executorService.submit(() -> {
          while (countDownLatch.getCount() > 0) {
            try {
              final ScrapeResponse response = this.scrapeResponseQueue.poll(1, TimeUnit.SECONDS);
              if (response == null)
                continue;
              logger.log(Level.INFO, String.format("Returning scrapeId: %s", response.getScrapeId()));
              this.blockingStub.writeResponseToProxy(response);
            }
            catch (InterruptedException e) {
              e.printStackTrace();
            }
          }
          logger.log(Level.INFO, "Exiting");
        });

        countDownLatch.await();
        logger.log(Level.INFO, String.format("Disconnected from proxy at %s", this.hostname));

        this.waitUntilDisconnected();
      }
      catch (StatusRuntimeException e) {
        logger.log(Level.INFO, String.format("Cannot connect to proxy at %s [%s]", this.hostname, e.getMessage()));
      }

      if (!reconnect)
        break;

      Thread.sleep(2000);
    }
  }

  public void waitUntilDisconnected() {

  }

  public void shutdown()
      throws InterruptedException {
    this.channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  public long registerAgent() {
    final RegisterAgentRequest request = RegisterAgentRequest.newBuilder().setHostname(Utils.getHostName()).build();
    final RegisterAgentResponse response = this.blockingStub.registerAgent(request);
    this.agentIdRef.set(response.getAgentId());
    return this.agentIdRef.get();
  }

  public void registerPaths() {
    // Register paths
    for (Map<String, String> agentConfig : this.agentConfigs) {
      System.out.println(agentConfig);
      final String path = agentConfig.get("path");
      final String url = agentConfig.get("url");
      long pathId = this.registerPath(path);
      System.out.println(pathId);
      this.pathContextMap.put(path, new PathContext(pathId, path, url));
    }
  }

  public long registerPath(final String path) {
    final RegisterPathRequest request = RegisterPathRequest.newBuilder()
                                                           .setAgentId(this.agentIdRef.get())
                                                           .setPath(path)
                                                           .build();
    final RegisterPathResponse response = this.blockingStub.registerPath(request);
    return response.getPathId();
  }

}
