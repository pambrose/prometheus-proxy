package io.prometheus.common;

import com.github.kristofa.brave.Brave;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;

import static java.lang.String.format;

public abstract class GenericService
    extends AbstractExecutionThreadService
    implements Closeable {

  private static final Logger logger = LoggerFactory.getLogger(GenericService.class);

  private final ConfigVals            configVals;
  private final MetricsService        metricsService;
  private final ZipkinReporterService zipkinReporterService;
  private final boolean               testMode;

  public GenericService(final ConfigVals configVals,
                        final MetricsConfig metricsConfig,
                        final ZipkinConfig zipkinConfig,
                        final boolean testMode) {
    this.configVals = configVals;
    this.testMode = testMode;

    if (metricsConfig.enabled()) {
      final int port = metricsConfig.port();
      final String path = metricsConfig.path();
      logger.info("Metrics server enabled with {} /{}", port, path);
      this.metricsService = new MetricsService(port, path);
      SystemMetrics.initialize(metricsConfig.standardExportsEnabled(),
                               metricsConfig.memoryPoolsExportsEnabled(),
                               metricsConfig.garbageCollectorExportsEnabled(),
                               metricsConfig.threadExportsEnabled(),
                               metricsConfig.classLoadingExportsEnabled(),
                               metricsConfig.versionInfoExportsEnabled());
    }
    else {
      logger.info("Metrics server disabled");
      this.metricsService = null;
    }

    if (zipkinConfig.enabled()) {
      final String zipkinHost = format("http://%s:%d/%s",
                                       zipkinConfig.hostname(), zipkinConfig.port(), zipkinConfig.path());
      logger.info("Zipkin reporter enabled for {}", zipkinHost);
      this.zipkinReporterService = new ZipkinReporterService(zipkinHost, zipkinConfig.serviceName());
    }
    else {
      logger.info("Zipkin reporter disabled");
      this.zipkinReporterService = null;
    }
  }

  @Override
  protected void startUp()
      throws Exception {
    super.startUp();
    if (this.isMetricsEnabled())
      this.metricsService.startAsync();
    Runtime.getRuntime().addShutdownHook(Utils.shutDownHookAction(this));
  }

  @Override
  protected void shutDown()
      throws Exception {
    if (this.isMetricsEnabled())
      this.metricsService.stopAsync();
    if (this.isZipkinEnabled())
      this.zipkinReporterService.shutDown();
    super.shutDown();
  }

  @Override
  public void close()
      throws IOException {
    this.stopAsync();
  }

  public boolean isMetricsEnabled() { return this.metricsService != null; }

  public boolean isTestMode() { return this.testMode; }

  public boolean isZipkinEnabled() { return this.zipkinReporterService != null; }

  protected MetricsService getMetricsService() { return this.metricsService; }

  public ZipkinReporterService getZipkinReporterService() { return this.zipkinReporterService; }

  public Brave getBrave() { return this.getZipkinReporterService().getBrave(); }

  protected ConfigVals getGenericConfigVals() { return this.configVals; }
}
