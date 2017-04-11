package com.sudothought;

import com.cinch.grpc.AgentRegisterRequest;
import com.cinch.grpc.AgentRegisterResponse;
import com.cinch.grpc.ProxyServiceGrpc;
import com.sudothought.args.AgentArgs;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Agent {

  private static final Logger logger = Logger.getLogger(Agent.class.getName());

  private final ManagedChannel                            channel;
  private final ProxyServiceGrpc.ProxyServiceBlockingStub blockingStub;

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
    ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(host, port)
                                                                   .usePlaintext(true);
    this.channel = channelBuilder.build();
    this.blockingStub = ProxyServiceGrpc.newBlockingStub(this.channel);
  }

  public static void main(final String[] argv)
      throws Exception {

    final AgentArgs agentArgs = new AgentArgs();
    agentArgs.parseArgs(Agent.class.getName(), argv);

    Agent agent = new Agent(agentArgs.proxy);
    try {
      agent.registerAgent();
    }
    finally {
      agent.shutdown();
    }
  }

  public void shutdown()
      throws InterruptedException {
    this.channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  public void registerAgent() {
    final AgentRegisterRequest request = AgentRegisterRequest.newBuilder().setHostname(Utils.getHostName()).build();
    try {
      final AgentRegisterResponse response = this.blockingStub.registerAgent(request);
      System.out.println(response.getAgentId());
    }
    catch (StatusRuntimeException e) {
      logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
    }
  }

}
