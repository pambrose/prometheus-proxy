package io.prometheus.common;

import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.codahale.metrics.health.jvm.ThreadDeadlockHealthCheck;
import com.github.kristofa.brave.Brave;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.ServiceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.String.format;

public abstract class GenericService
    extends AbstractExecutionThreadService
    implements Closeable {

  private static final Logger logger = LoggerFactory.getLogger(GenericService.class);

  private final MetricRegistry      metricRegistry      = new MetricRegistry();
  private final HealthCheckRegistry healthCheckRegistry = new HealthCheckRegistry();
  private final List<Service>       services            = Lists.newArrayList(this);
  private final ConfigVals            configVals;
  private final boolean               testMode;
  private final JmxReporter           jmxReporter;
  private final MetricsService        metricsService;
  private final ZipkinReporterService zipkinReporterService;
  private final AdminService          adminService;
  private ServiceManager serviceManager = null;

  public GenericService(final ConfigVals configVals,
                        final AdminConfig adminConfig,
                        final MetricsConfig metricsConfig,
                        final ZipkinConfig zipkinConfig,
                        final boolean testMode) {
    this.configVals = configVals;
    this.testMode = testMode;

    this.jmxReporter = JmxReporter.forRegistry(this.metricRegistry).build();

    if (adminConfig.enabled()) {
      this.adminService = new AdminService(this,
                                           adminConfig.port(),
                                           adminConfig.pingPath(),
                                           adminConfig.healthCheckPath(),
                                           adminConfig.theadtDumpPath());
      this.addService(this.adminService);
    }
    else {
      logger.info("Admin service disabled");
      this.adminService = null;
    }

    if (metricsConfig.enabled()) {
      final int port = metricsConfig.port();
      final String path = metricsConfig.path();
      this.metricsService = new MetricsService(port, path);
      this.addService(this.metricsService);
      SystemMetrics.initialize(metricsConfig.standardExportsEnabled(),
                               metricsConfig.memoryPoolsExportsEnabled(),
                               metricsConfig.garbageCollectorExportsEnabled(),
                               metricsConfig.threadExportsEnabled(),
                               metricsConfig.classLoadingExportsEnabled(),
                               metricsConfig.versionInfoExportsEnabled());
    }
    else {
      logger.info("Metrics service disabled");
      this.metricsService = null;
    }

    if (zipkinConfig.enabled()) {
      final String zipkinUrl = format("http://%s:%d/%s",
                                      zipkinConfig.hostname(), zipkinConfig.port(), zipkinConfig.path());
      this.zipkinReporterService = new ZipkinReporterService(zipkinUrl, zipkinConfig.serviceName());
      this.addService(this.zipkinReporterService);
    }
    else {
      logger.info("Zipkin reporter service disabled");
      this.zipkinReporterService = null;
    }

    this.addListener(new GenericServiceListener(this), MoreExecutors.directExecutor());
  }

  public void init() {
    this.serviceManager = new ServiceManager(this.services);
    this.serviceManager.addListener(this.newListener());
    this.registerHealtChecks();
  }

  @Override
  protected void startUp()
      throws Exception {
    super.startUp();
    if (this.jmxReporter != null)
      this.jmxReporter.start();
    if (this.isMetricsEnabled())
      this.metricsService.startAsync();
    if (adminService != null)
      this.adminService.startAsync();
    Runtime.getRuntime().addShutdownHook(Utils.shutDownHookAction(this));
  }

  @Override
  protected void shutDown()
      throws Exception {
    if (adminService != null)
      this.adminService.shutDown();
    if (this.isMetricsEnabled())
      this.metricsService.stopAsync();
    if (this.isZipkinEnabled())
      this.zipkinReporterService.shutDown();
    if (this.jmxReporter != null)
      this.jmxReporter.stop();
    super.shutDown();
  }

  @Override
  public void close()
      throws IOException {
    this.stopAsync();
  }

  protected void addService(final Service service) {
    this.services.add(service);
  }

  protected void addServices(final Service service, final Service... services) {
    this.services.addAll(Lists.asList(service, services));
  }

  protected void registerHealtChecks() {
    this.getHealthCheckRegistry().register("thread_deadlock", new ThreadDeadlockHealthCheck());
    if (this.isMetricsEnabled())
      this.getHealthCheckRegistry().register("metrics_service", this.metricsService.getHealthCheck());

    this.getHealthCheckRegistry()
        .register(
            "all_services_running",
            new HealthCheck() {
              @Override
              protected Result check()
                  throws Exception {
                final ImmutableMultimap<State, Service> sbs = serviceManager.servicesByState();
                return sbs.keySet().size() == 1 && sbs.containsKey(State.RUNNING)
                       ? Result.healthy()
                       : Result.unhealthy("Incorrect state: "
                                              + Joiner.on(", ")
                                                      .join(sbs.entries()
                                                               .stream()
                                                               .filter(kv -> kv.getKey() != State.RUNNING)
                                                               .peek(kv -> logger.warn("Incorrect state - {}: {}",
                                                                                       kv.getKey(), kv.getValue()))
                                                               .map(kv -> format("%s: %s", kv.getKey(), kv.getValue()))
                                                               .collect(Collectors.toList())));
              }
            });
  }

  protected ServiceManager.Listener newListener() {
    return new ServiceManager.Listener() {
      @Override
      public void healthy() {
        logger.info("All {} services healthy", this.getClass().getSimpleName());
      }

      @Override
      public void stopped() {
        logger.info("All {} services stopped", this.getClass().getSimpleName());
      }

      @Override
      public void failure(final Service service) {
        logger.info("{} service failed: {}", this.getClass().getSimpleName(), service);
      }
    };
  }

  public MetricRegistry getMetricRegistry() { return this.metricRegistry; }

  public HealthCheckRegistry getHealthCheckRegistry() { return this.healthCheckRegistry; }

  public boolean isMetricsEnabled() { return this.metricsService != null; }

  public boolean isTestMode() { return this.testMode; }

  public boolean isZipkinEnabled() { return this.zipkinReporterService != null; }

  protected MetricsService getMetricsService() { return this.metricsService; }

  public ZipkinReporterService getZipkinReporterService() { return this.zipkinReporterService; }

  public Brave getBrave() { return this.getZipkinReporterService().getBrave(); }

  protected ConfigVals getGenericConfigVals() { return this.configVals; }
}
