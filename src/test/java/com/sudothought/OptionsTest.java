package com.sudothought;

import com.sudothought.agent.AgentOptions;
import com.sudothought.common.ConfigVals;
import com.sudothought.proxy.ProxyOptions;
import org.junit.Test;

import java.util.List;

import static com.google.common.collect.Iterables.toArray;
import static com.sudothought.common.EnvVars.AGENT_CONFIG;
import static com.sudothought.common.EnvVars.PROXY_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.util.Lists.newArrayList;

public class OptionsTest {

  @Test
  public void verifyDefaultValues() {
    final ConfigVals configVals = readProxyOptions(newArrayList());
    assertThat(configVals.proxy.http.port).isEqualTo(8080);
    assertThat(configVals.proxy.zipkin.enabled).isEqualTo(false);
  }

  @Test
  public void verifyConfValues() {
    final ConfigVals configVals = readProxyOptions(newArrayList("--config",
                                                                "https://dl.dropboxusercontent.com/u/481551/prometheus/junit-tests.conf"));
    assertThat(configVals.proxy.http.port).isEqualTo(8181);
    assertThat(configVals.proxy.zipkin.enabled).isEqualTo(true);
  }

  @Test
  public void verifyUnquotedPropValue() {
    final ConfigVals configVals = readProxyOptions(newArrayList("-Dproxy.http.port=9393", "-Dproxy.zipkin.enabled=true"));
    assertThat(configVals.proxy.http.port).isEqualTo(9393);
    assertThat(configVals.proxy.zipkin.enabled).isEqualTo(true);
  }

  @Test
  public void verifyQuotedPropValue() {
    final ConfigVals configVals = readProxyOptions(newArrayList("-D\"proxy.http.port=9394\""));
    assertThat(configVals.proxy.http.port).isEqualTo(9394);
  }

  @Test
  public void verifyPathConfigs() {
    final ConfigVals configVals = readAgentOptions(newArrayList("--config",
                                                                "https://dl.dropboxusercontent.com/u/481551/prometheus/junit-tests.conf"));
    assertThat(configVals.agent.pathConfigs.size()).isEqualTo(3);
  }


  public void verifyProxyDefaults() {
    final ProxyOptions options = new ProxyOptions();
    options.parseArgs(Proxy.class.getName(), toArray(newArrayList(), String.class));
    options.readConfig(PROXY_CONFIG.getText(), false);
    options.applyDynamicParams();

    final ConfigVals configVals = new ConfigVals(options.getConfig());
    options.assignOptions(configVals);

    assertThat(options.getHttpPort()).isEqualTo(8080);
    assertThat(options.getGrpcPort()).isEqualTo(50021);

  }

  public void verifyAgentDefaults() {
    AgentOptions options = new AgentOptions();
    options.parseArgs(Agent.class.getName(), toArray(newArrayList("--name", "test-name", "--proxy", "host5"), String.class));
    options.readConfig(AGENT_CONFIG.getText(), false);
    options.applyDynamicParams();

    final ConfigVals configVals = new ConfigVals(options.getConfig());
    options.assignOptions(configVals);

    assertThat(options.getEnableMetrics()).isEqualTo(false);
    assertThat(options.getDynamicParams().size()).isEqualTo(0);
    assertThat(options.getAgentName()).isEqualTo("test-name");
    assertThat(options.getProxyHostname()).isEqualTo("host5");
  }

  private ConfigVals readProxyOptions(final List<String> argList) {
    final ProxyOptions options = new ProxyOptions();
    options.parseArgs(Proxy.class.getName(), toArray(argList, String.class));
    options.readConfig(PROXY_CONFIG.getText(), false);
    options.applyDynamicParams();

    final ConfigVals configVals = new ConfigVals(options.getConfig());
    options.assignOptions(configVals);
    return configVals;
  }

  private ConfigVals readAgentOptions(final List<String> argList) {
    AgentOptions options = new AgentOptions();
    options.parseArgs(Agent.class.getName(), toArray(argList, String.class));
    options.readConfig(AGENT_CONFIG.getText(), false);
    options.applyDynamicParams();

    final ConfigVals configVals = new ConfigVals(options.getConfig());
    options.assignOptions(configVals);
    return configVals;
  }
}