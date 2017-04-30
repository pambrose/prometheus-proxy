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
    AdminConfig c = AdminConfig.create(vals.agent.admin.enabled, -1, vals.agent.admin);
    assertThat(c.enabled()).isTrue();

    vals = configVals("agent.admin.port=888");
    c = AdminConfig.create(vals.agent.admin.enabled, vals.agent.admin.port, vals.agent.admin);
    assertThat(c.enabled()).isFalse();
    assertThat(c.port()).isEqualTo(888);

    c = AdminConfig.create(true, 444, configVals("agent.admin.pingPath=a pingpath val").agent.admin);
    assertThat(c.pingPath()).isEqualTo("a pingpath val");

    c = AdminConfig.create(true, 444, configVals("agent.admin.healthCheckPath=a healthCheckPath val").agent.admin);
    assertThat(c.healthCheckPath()).isEqualTo("a healthCheckPath val");

    c = AdminConfig.create(true, 444, configVals("agent.admin.theadtDumpPath=a theadtDumpPath val").agent.admin);
    assertThat(c.theadtDumpPath()).isEqualTo("a theadtDumpPath val");
  }

  @Test
  public void metricsConfigTest() {
    MetricsConfig c = MetricsConfig.create(true, 555, configVals("agent.metrics.enabled=true").agent.metrics);
    assertThat(c.enabled()).isTrue();

    c = MetricsConfig.create(true, 555, configVals("agent.metrics.hostname=testval").agent.metrics);
    assertThat(c.port()).isEqualTo(555);

    c = MetricsConfig.create(true, 555, configVals("agent.metrics.path=a path val").agent.metrics);
    assertThat(c.path()).isEqualTo("a path val");

    c = MetricsConfig.create(true, 555, configVals("agent.metrics.standardExportsEnabled=true").agent.metrics);
    assertThat(c.standardExportsEnabled()).isTrue();

    c = MetricsConfig.create(true, 555, configVals("agent.metrics.memoryPoolsExportsEnabled=true").agent.metrics);
    assertThat(c.memoryPoolsExportsEnabled()).isTrue();

    c = MetricsConfig.create(true, 555, configVals("agent.metrics.garbageCollectorExportsEnabled=true").agent.metrics);
    assertThat(c.garbageCollectorExportsEnabled()).isTrue();

    c = MetricsConfig.create(true, 555, configVals("agent.metrics.threadExportsEnabled=true").agent.metrics);
    assertThat(c.threadExportsEnabled()).isTrue();

    c = MetricsConfig.create(true, 555, configVals("agent.metrics.classLoadingExportsEnabled=true").agent.metrics);
    assertThat(c.classLoadingExportsEnabled()).isTrue();

    c = MetricsConfig.create(true, 555, configVals("agent.metrics.versionInfoExportsEnabled=true").agent.metrics);
    assertThat(c.versionInfoExportsEnabled()).isTrue();
  }

  @Test
  public void zipkinConfigTest() {
    ZipkinConfig c = ZipkinConfig.create(configVals("agent.internal.zipkin.enabled=true").agent.internal.zipkin);
    assertThat(c.enabled()).isTrue();

    c = ZipkinConfig.create(configVals("agent.internal.zipkin.hostname=testval").agent.internal.zipkin);
    assertThat(c.hostname()).isEqualTo("testval");

    c = ZipkinConfig.create(configVals("agent.internal.zipkin.port=999").agent.internal.zipkin);
    assertThat(c.port()).isEqualTo(999);

    c = ZipkinConfig.create(configVals("agent.internal.zipkin.path=a path val").agent.internal.zipkin);
    assertThat(c.path()).isEqualTo("a path val");

    c = ZipkinConfig.create(configVals("agent.internal.zipkin.serviceName=a service name").agent.internal.zipkin);
    assertThat(c.serviceName()).isEqualTo("a service name");
  }
}
