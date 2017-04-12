package com.sudothought;

import com.cinch.grpc.AgentInfo;
import com.cinch.grpc.AgentRegisterRequest;
import com.cinch.grpc.AgentRegisterResponse;
import com.cinch.grpc.PathRegisterRequest;
import com.cinch.grpc.PathRegisterResponse;
import com.cinch.grpc.ProxyServiceGrpc;
import com.cinch.grpc.ScrapeRequest;
import com.cinch.grpc.ScrapeResponse;
import com.google.common.collect.Maps;
import com.sudothought.args.AgentArgs;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
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
import java.util.logging.Level;
import java.util.logging.Logger;

public class Agent {

  private static final Logger logger = Logger.getLogger(Agent.class.getName());

  private final BlockingQueue<ScrapeResponse> scrapeResponseQueue = new ArrayBlockingQueue<>(1000);

  // Map path to agent_id
  private final Map<String, String> pathMap = Maps.newConcurrentMap();
  private final AtomicBoolean       stopped = new AtomicBoolean(false);

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
    long agent_id = agent.registerAgent();
    System.out.println(agent_id);

    // Register paths
    try {
      for (Map<String, String> agent_config : getAgentConfigs(agentArgs.config)) {
        System.out.println(agent_config);
        final String path = agent_config.get("path");
        final String url = agent_config.get("url");
        long path_id = agent.registerPath(agent_id, path);
        System.out.println(path_id);
        agent.pathMap.put(path, url);
      }
    }
    catch (FileNotFoundException e) {
      logger.log(Level.INFO, String.format("Invalid config file name: %s", agentArgs.config));
    }

    final ExecutorService executorService = Executors.newFixedThreadPool(2);

    executorService.submit(() -> {
      agent.asyncStub.readRequestsFromProxy(
          AgentInfo.newBuilder().setAgentId(agent_id).build(),
          new StreamObserver<ScrapeRequest>() {
            @Override
            public void onNext(ScrapeRequest scrapeRequest) {
              final ScrapeResponse response = ScrapeResponse.newBuilder()
                                                            .setAgentId(scrapeRequest.getAgentId())
                                                            .setScrapeId(scrapeRequest.getScrapeId())
                                                            .setValid(true)
                                                            .setStatusCode(200)
                                                            .setText("This is a result for " + scrapeRequest.getPath())
                                                            .build();
              agent.scrapeResponseQueue.add(response);
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
          final ScrapeResponse response = agent.scrapeResponseQueue.take();
          agent.blockingStub.writeResponseToProxy(response);
        }
        catch (Exception e) {
          e.printStackTrace();
        }
      }
    });
  }

  private static List<Map<String, String>> getAgentConfigs(final String filename)
      throws FileNotFoundException {
    final Yaml yaml = new Yaml();
    final InputStream input = new FileInputStream(new File(filename));
    final Map<String, List<Map<String, String>>> data = (Map<String, List<Map<String, String>>>) yaml.load(input);
    return data.get("agent_configs");
  }

  public void exchangeMsgs()
      throws InterruptedException {
    final CountDownLatch finishLatch = new CountDownLatch(1);
    StreamObserver<ScrapeResponse> requestObserver = this.asyncStub.exchangeScrapeMsgs(
        new StreamObserver<ScrapeRequest>() {
          @Override
          public void onNext(ScrapeRequest scrapeRequest) {
            final ScrapeResponse response = ScrapeResponse.newBuilder()
                                                          .setAgentId(scrapeRequest.getAgentId())
                                                          .setScrapeId(scrapeRequest.getScrapeId())
                                                          .setValid(true)
                                                          .setStatusCode(200)
                                                          .setText("This is a result for " + scrapeRequest.getPath())
                                                          .build();
            scrapeResponseQueue.add(response);
          }

          @Override
          public void onError(Throwable t) {
            Status status = Status.fromThrowable(t);
            logger.log(Level.WARNING, "Failed: {0}", status);
            finishLatch.countDown();
          }

          @Override
          public void onCompleted() {
            finishLatch.countDown();
          }
        });

    try {
      while (!stopped.get()) {
        final ScrapeResponse response = scrapeResponseQueue.take();
        requestObserver.onNext(response);
      }
    }
    catch (RuntimeException e) {
      requestObserver.onError(e);
      throw e;
    }
    // Mark the end of requests
    requestObserver.onCompleted();

    // Receiving happens asynchronously
    finishLatch.await(1, TimeUnit.MINUTES);
  }

  public void shutdown()
      throws InterruptedException {
    this.channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  public long registerAgent() {
    final AgentRegisterRequest request = AgentRegisterRequest.newBuilder().setHostname(Utils.getHostName()).build();
    final AgentRegisterResponse response = this.blockingStub.registerAgent(request);
    return response.getAgentId();
  }

  public long registerPath(final long agent_id, final String path) {
    final PathRegisterRequest request = PathRegisterRequest.newBuilder()
                                                           .setAgentId(agent_id)
                                                           .setPath(path)
                                                           .build();
    final PathRegisterResponse response = this.blockingStub.registerPath(request);
    return response.getPathId();
  }

}
