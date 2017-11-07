/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus;

import io.prometheus.agent.AgentOptions;
import io.prometheus.common.ConfigVals;
import io.prometheus.proxy.ProxyOptions;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.util.Lists.newArrayList;

public class OptionsTest {

  private static String CONFIG = "https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/etc/test-configs/junit-test.conf";

  @Test
  public void verifyDefaultValues() {
    final ConfigVals configVals = readProxyOptions(newArrayList());
    assertThat(configVals.proxy.http.port).isEqualTo(8080);
    assertThat(configVals.proxy.internal.zipkin.enabled).isEqualTo(false);
  }

  @Test
  public void verifyConfValues() {
    final ConfigVals configVals = readProxyOptions(newArrayList("--config", CONFIG));
    assertThat(configVals.proxy.http.port).isEqualTo(8181);
    assertThat(configVals.proxy.internal.zipkin.enabled).isEqualTo(true);
  }

  @Test
  public void verifyUnquotedPropValue() {
    final ConfigVals configVals = readProxyOptions(newArrayList("-Dproxy.http.port=9393", "-Dproxy.internal.zipkin.enabled=true"));
    assertThat(configVals.proxy.http.port).isEqualTo(9393);
    assertThat(configVals.proxy.internal.zipkin.enabled).isEqualTo(true);
  }

  @Test
  public void verifyQuotedPropValue() {
    final ConfigVals configVals = readProxyOptions(newArrayList("-D\"proxy.http.port=9394\""));
    assertThat(configVals.proxy.http.port).isEqualTo(9394);
  }

  @Test
  public void verifyPathConfigs() {
    final ConfigVals configVals = readAgentOptions(newArrayList("--config", CONFIG));
    assertThat(configVals.agent.pathConfigs.size()).isEqualTo(3);
  }


  public void verifyProxyDefaults() {
    final ProxyOptions options = new ProxyOptions(newArrayList());

    assertThat(options.getProxyPort()).isEqualTo(8080);
    assertThat(options.getAgentPort()).isEqualTo(50021);
  }

  public void verifyAgentDefaults() {
    AgentOptions options = new AgentOptions(newArrayList("--name", "test-name", "--proxy", "host5"),
                                            false);

    assertThat(options.isMetricsEnabled()).isEqualTo(false);
    assertThat(options.getDynamicParams().size()).isEqualTo(0);
    assertThat(options.getAgentName()).isEqualTo("test-name");
    assertThat(options.getProxyHostname()).isEqualTo("host5");
  }

  private ConfigVals readProxyOptions(final List<String> argList) {
    final ProxyOptions options = new ProxyOptions(argList);
    return options.getConfigVals();
  }

  private ConfigVals readAgentOptions(final List<String> argList) {
    AgentOptions options = new AgentOptions(argList, false);
    return options.getConfigVals();
  }
}