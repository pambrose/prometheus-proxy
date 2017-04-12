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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Agent {

  private static final Logger logger = Logger.getLogger(Agent.class.getName());

  private final BlockingQueue<ScrapeResponse> scrapeResponseQueue = new ArrayBlockingQueue<>(1000);

  // Map path to PathContext
  private final Map<String, PathContext> pathContextMap = Maps.newConcurrentMap();
  private final AtomicBoolean            stopped        = new AtomicBoolean(false);

  private final ManagedChannel                            channel;
  private final ProxyServiceGrpc.ProxyServiceBlockingStub blockingStub;
  private final ProxyServiceGrpc.ProxyServiceStub         asyncStub;

  public Agent(String hostname) {
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
    final ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(host, port).usePlaintext(true);
    this.channel = channelBuilder.build();
    this.blockingStub = ProxyServiceGrpc.newBlockingStub(this.channel);
    this.asyncStub = ProxyServiceGrpc.newStub(this.channel);
  }

  public static void main(final String[] argv)
      throws Exception {

    final AgentArgs agentArgs = new AgentArgs();
    agentArgs.parseArgs(Agent.class.getName(), argv);

    final Agent agent = new Agent(agentArgs.proxy);

    // Register Agent
    long agentId = agent.registerAgent();
    System.out.println(agentId);

    // Register paths
    try {
      for (Map<String, String> agentConfig : getAgentConfigs(agentArgs.config)) {
        System.out.println(agentConfig);
        final String path = agentConfig.get("path");
        final String url = agentConfig.get("url");
        long pathId = agent.registerPath(agentId, path);
        System.out.println(pathId);
        agent.pathContextMap.put(path, new PathContext(pathId, path, url));
      }
    }
    catch (FileNotFoundException e) {
      logger.log(Level.WARNING, String.format("Invalid config file name: %s", agentArgs.config));
    }

    final ExecutorService executorService = Executors.newFixedThreadPool(2);

    executorService.submit(() -> {
      agent.asyncStub.readRequestsFromProxy(
          AgentInfo.newBuilder().setAgentId(agentId).build(),
          new StreamObserver<ScrapeRequest>() {
            @Override
            public void onNext(ScrapeRequest scrapeRequest) {
              final PathContext pathContext = agent.pathContextMap.get(scrapeRequest.getPath());
              ScrapeResponse scrapResponse;
              try {
                logger.log(Level.INFO, String.format("Fetching: %s", pathContext.getUrl()));
                final Response response = pathContext.fetchUrl();
                logger.log(Level.INFO, String.format("Fetched: %s", pathContext.getUrl()));
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

              logger.log(Level.INFO, String.format("Adding to scrapeResponseQueue: %s", pathContext.getUrl()));
              try {
                agent.scrapeResponseQueue.put(scrapResponse);
              }
              catch (InterruptedException e) {
                e.printStackTrace();
              }
            }

            @Override
            public void onError(Throwable t) {
              Status status = Status.fromThrowable(t);
              logger.log(Level.WARNING, "Failed: {0}", status);
            }

            @Override
            public void onCompleted() {
              logger.log(Level.INFO, "Completed");
            }
          });

    });

    executorService.submit(() -> {
      while (!agent.stopped.get()) {
        try {
          logger.log(Level.INFO, "Waiting on scrapeResponseQueue");
          final ScrapeResponse response = agent.scrapeResponseQueue.take();
          logger.log(Level.INFO, String.format("Returning scrapeId: %s", response.getScrapeId()));
          agent.blockingStub.writeResponseToProxy(response);
        }
        catch (Exception e) {
          e.printStackTrace();
        }
      }
      logger.log(Level.INFO, "Exiting");
    });

    Thread.sleep(Integer.MAX_VALUE);
  }

  private static List<Map<String, String>> getAgentConfigs(final String filename)
      throws FileNotFoundException {
    final Yaml yaml = new Yaml();
    final InputStream input = new FileInputStream(new File(filename));
    final Map<String, List<Map<String, String>>> data = (Map<String, List<Map<String, String>>>) yaml.load(input);
    return data.get("agent_configs");
  }


  public void shutdown()
      throws InterruptedException {
    this.channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  public long registerAgent() {
    final RegisterAgentRequest request = RegisterAgentRequest.newBuilder().setHostname(Utils.getHostName()).build();
    final RegisterAgentResponse response = this.blockingStub.registerAgent(request);
    return response.getAgentId();
  }

  public long registerPath(final long agentId, final String path) {
    final RegisterPathRequest request = RegisterPathRequest.newBuilder()
                                                           .setAgentId(agentId)
                                                           .setPath(path)
                                                           .build();
    final RegisterPathResponse response = this.blockingStub.registerPath(request);
    return response.getPathId();
  }

}
