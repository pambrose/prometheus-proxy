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

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;
import io.prometheus.common.AdminConfig;
import io.prometheus.common.ConfigVals;
import io.prometheus.common.MetricsConfig;
import io.prometheus.common.ZipkinConfig;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AutoValueTest {

  private ConfigVals configVals(final String str) {
    final Config config = ConfigFactory.parseString(str, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF));
    return new ConfigVals(config.withFallback(ConfigFactory.load().resolve()).resolve());
  }

  @Test
  public void adminConfigTest() {
    ConfigVals vals = configVals("agent.admin.enabled=true");
    AdminConfig c = AdminConfig.Companion.create(vals.agent.admin.enabled, -1, vals.agent.admin);
    assertThat(c.getEnabled()).isTrue();

    vals = configVals("agent.admin.port=888");
    c = AdminConfig.Companion.create(vals.agent.admin.enabled, vals.agent.admin.port, vals.agent.admin);
    assertThat(c.getEnabled()).isFalse();
    assertThat(c.getPort()).isEqualTo(888);

    c = AdminConfig.Companion.create(true, 444, configVals("agent.admin.pingPath=a pingpath val").agent.admin);
    assertThat(c.getPingPath()).isEqualTo("a pingpath val");

    c = AdminConfig.Companion.create(true, 444, configVals("agent.admin.versionPath=a versionpath val").agent.admin);
    assertThat(c.getVersionPath()).isEqualTo("a versionpath val");

    c = AdminConfig.Companion.create(true, 444, configVals("agent.admin.healthCheckPath=a healthCheckPath val").agent.admin);
    assertThat(c.getHealthCheckPath()).isEqualTo("a healthCheckPath val");

    c = AdminConfig.Companion.create(true, 444, configVals("agent.admin.threadDumpPath=a threadDumpPath val").agent.admin);
    assertThat(c.getThreadDumpPath()).isEqualTo("a threadDumpPath val");
  }

  @Test
  public void metricsConfigTest() {
    MetricsConfig c = MetricsConfig.Companion.create(true, 555, configVals("agent.metrics.enabled=true").agent.metrics);
    assertThat(c.getEnabled()).isTrue();

    c = MetricsConfig.Companion.create(true, 555, configVals("agent.metrics.hostname=testval").agent.metrics);
    assertThat(c.getPort()).isEqualTo(555);

    c = MetricsConfig.Companion.create(true, 555, configVals("agent.metrics.path=a path val").agent.metrics);
    assertThat(c.getPath()).isEqualTo("a path val");

    c = MetricsConfig.Companion.create(true, 555, configVals("agent.metrics.standardExportsEnabled=true").agent.metrics);
    assertThat(c.getStandardExportsEnabled()).isTrue();

    c = MetricsConfig.Companion.create(true, 555, configVals("agent.metrics.memoryPoolsExportsEnabled=true").agent.metrics);
    assertThat(c.getMemoryPoolsExportsEnabled()).isTrue();

    c = MetricsConfig.Companion.create(true, 555, configVals("agent.metrics.garbageCollectorExportsEnabled=true").agent.metrics);
    assertThat(c.getGarbageCollectorExportsEnabled()).isTrue();

    c = MetricsConfig.Companion.create(true, 555, configVals("agent.metrics.threadExportsEnabled=true").agent.metrics);
    assertThat(c.getThreadExportsEnabled()).isTrue();

    c = MetricsConfig.Companion.create(true, 555, configVals("agent.metrics.classLoadingExportsEnabled=true").agent.metrics);
    assertThat(c.getClassLoadingExportsEnabled()).isTrue();

    c = MetricsConfig.Companion.create(true, 555, configVals("agent.metrics.versionInfoExportsEnabled=true").agent.metrics);
    assertThat(c.getVersionInfoExportsEnabled()).isTrue();
  }

  @Test
  public void zipkinConfigTest() {
    ZipkinConfig c = ZipkinConfig.Companion.create(configVals("agent.internal.zipkin.enabled=true").agent.internal.zipkin);
    assertThat(c.getEnabled()).isTrue();

    c = ZipkinConfig.Companion.create(configVals("agent.internal.zipkin.hostname=testval").agent.internal.zipkin);
    assertThat(c.getHostname()).isEqualTo("testval");

    c = ZipkinConfig.Companion.create(configVals("agent.internal.zipkin.port=999").agent.internal.zipkin);
    assertThat(c.getPort()).isEqualTo(999);

    c = ZipkinConfig.Companion.create(configVals("agent.internal.zipkin.path=a path val").agent.internal.zipkin);
    assertThat(c.getPath()).isEqualTo("a path val");

    c = ZipkinConfig.Companion.create(configVals("agent.internal.zipkin.serviceName=a service name").agent.internal.zipkin);
    assertThat(c.getServiceName()).isEqualTo("a service name");
  }
}
