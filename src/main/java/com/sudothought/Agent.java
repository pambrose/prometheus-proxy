package com.sudothought;

import com.cinch.grpc.AgentRegisterRequest;
import com.cinch.grpc.AgentRegisterResponse;
import com.cinch.grpc.PathRegisterRequest;
import com.cinch.grpc.PathRegisterResponse;
import com.cinch.grpc.ProxyServiceGrpc;
import com.sudothought.args.AgentArgs;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
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
    final ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(host, port).usePlaintext(true);
    this.channel = channelBuilder.build();
    this.blockingStub = ProxyServiceGrpc.newBlockingStub(this.channel);
  }

  public static void main(final String[] argv)
      throws Exception {

    final AgentArgs agentArgs = new AgentArgs();
    agentArgs.parseArgs(Agent.class.getName(), argv);


    Agent agent = new Agent(agentArgs.proxy);
    try {
      long agent_id = agent.registerAgent();
      System.out.println(agent_id);

      try {
        for (Map<String, String> agent_config : getAgentConfigs(agentArgs.config)) {
          System.out.println(agent_config);
          long path_id = agent.registerPath(agent_id, agent_config.get("path"));
          System.out.println(path_id);
        }
      }
      catch (FileNotFoundException e) {
        logger.log(Level.INFO, String.format("Invalid config file name: %s", agentArgs.config));
      }
    }
    catch (StatusRuntimeException e) {
      logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
    }
    finally {
      agent.shutdown();
    }
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
