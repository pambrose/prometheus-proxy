package io.prometheus;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;
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
  public void zipkinConfigTest() {
    ZipkinConfig z = ZipkinConfig.create(configVals("agent.internal.zipkin.enabled=true").agent.internal.zipkin);
    assertThat(z.enabled()).isTrue();

    z = ZipkinConfig.create(configVals("agent.internal.zipkin.hostname=testval").agent.internal.zipkin);
    assertThat(z.hostname()).isEqualTo("testval");

    z = ZipkinConfig.create(configVals("agent.internal.zipkin.port=999").agent.internal.zipkin);
    assertThat(z.port()).isEqualTo(999);

    z = ZipkinConfig.create(configVals("agent.internal.zipkin.path=a path val").agent.internal.zipkin);
    assertThat(z.path()).isEqualTo("a path val");

    z = ZipkinConfig.create(configVals("agent.internal.zipkin.serviceName=a service name").agent.internal.zipkin);
    assertThat(z.serviceName()).isEqualTo("a service name");
  }

  @Test
  public void metricsConfigTest() {
    MetricsConfig m = MetricsConfig.create(true, 555, configVals("agent.metrics.enabled=true").agent.metrics);
    assertThat(m.enabled()).isTrue();

    m = MetricsConfig.create(true, 555, configVals("agent.metrics.hostname=testval").agent.metrics);
    assertThat(m.port()).isEqualTo(555);

    m = MetricsConfig.create(true, 555, configVals("agent.metrics.path=a path val").agent.metrics);
    assertThat(m.path()).isEqualTo("a path val");

    m = MetricsConfig.create(true, 555, configVals("agent.metrics.standardExportsEnabled=true").agent.metrics);
    assertThat(m.standardExportsEnabled()).isTrue();

    m = MetricsConfig.create(true, 555, configVals("agent.metrics.memoryPoolsExportsEnabled=true").agent.metrics);
    assertThat(m.memoryPoolsExportsEnabled()).isTrue();

    m = MetricsConfig.create(true, 555, configVals("agent.metrics.garbageCollectorExportsEnabled=true").agent.metrics);
    assertThat(m.garbageCollectorExportsEnabled()).isTrue();

    m = MetricsConfig.create(true, 555, configVals("agent.metrics.threadExportsEnabled=true").agent.metrics);
    assertThat(m.threadExportsEnabled()).isTrue();

    m = MetricsConfig.create(true, 555, configVals("agent.metrics.classLoadingExportsEnabled=true").agent.metrics);
    assertThat(m.classLoadingExportsEnabled()).isTrue();

    m = MetricsConfig.create(true, 555, configVals("agent.metrics.versionInfoExportsEnabled=true").agent.metrics);
    assertThat(m.versionInfoExportsEnabled()).isTrue();

  }

}
