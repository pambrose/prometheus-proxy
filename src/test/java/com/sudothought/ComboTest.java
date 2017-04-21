package com.sudothought;

import com.sudothought.agent.Agent;
import com.sudothought.agent.AgentArgs;
import com.sudothought.common.ConfigVals;
import com.sudothought.common.Utils;
import com.sudothought.proxy.Proxy;
import com.sudothought.proxy.ProxyArgs;
import com.typesafe.config.Config;
import org.assertj.core.api.Assertions;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static com.sudothought.common.EnvVars.AGENT_CONFIG;
import static com.sudothought.common.EnvVars.PROXY_CONFIG;
import static com.sudothought.common.Utils.sleepForSecs;

public class ComboTest {

  private static Proxy proxy = null;
  private static Agent agent = null;

  @BeforeClass
  public static void setUp()
      throws Exception {

    final String[] argv = {"--config", "https://dl.dropboxusercontent.com/u/481551/prometheus/proxy.conf"};
    final ProxyArgs proxyArgs = new ProxyArgs();
    proxyArgs.parseArgs(Proxy.class.getName(), argv);

    final Config proxyConfig = Utils.readConfig(proxyArgs.config, PROXY_CONFIG, false);
    final ConfigVals proxyConfigVals = new ConfigVals(proxyConfig);
    proxyArgs.assignArgs(proxyConfigVals);

    proxy = new Proxy(proxyConfigVals,
                      proxyArgs.grpc_port,
                      proxyArgs.http_port,
                      !proxyArgs.disable_metrics,
                      proxyArgs.metrics_port,
                      "server1");
    proxy.start();

    final AgentArgs agentArgs = new AgentArgs();
    agentArgs.parseArgs(Agent.class.getName(), argv);

    final Config agentConfig = Utils.readConfig(agentArgs.config, AGENT_CONFIG, true);
    final ConfigVals configVals = new ConfigVals(agentConfig);
    agentArgs.assignArgs(configVals);

    agent = new Agent(configVals,
                      "server1",
                      agentArgs.agent_name,
                      agentArgs.proxy_host,
                      !agentArgs.disable_metrics,
                      agentArgs.metrics_port);
    agent.start();

  }

  @AfterClass
  public static void takeDown() {
    proxy.stop();
    agent.stop();
    sleepForSecs(15);
  }

  @Test
  public void test1() {

    sleepForSecs(15);

    Assertions.assertThat(4).isEqualTo(4);
  }
}
