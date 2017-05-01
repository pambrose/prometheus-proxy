package io.prometheus.common;

import com.codahale.metrics.health.HealthCheck;
import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.MoreExecutors;
import io.prometheus.client.exporter.MetricsServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import static java.lang.String.format;

public class MetricsService
    extends AbstractIdleService {

  private final int    port;
  private final String path;
  private final Server server;
  private final HealthCheck healthCheck =
      new HealthCheck() {
        @Override
        protected Result check()
            throws Exception {
          return server.isRunning() ? Result.healthy() : Result.unhealthy("Jetty server not running");
        }
      };

  public MetricsService(final int port, final String path) {
    this.port = port;
    this.path = path;
    this.server = new Server(this.port);

    final ServletContextHandler context = new ServletContextHandler();
    context.setContextPath("/");
    this.server.setHandler(context);
    context.addServlet(new ServletHolder(new MetricsServlet()), "/" + this.path);

    this.addListener(new GenericServiceListener(this), MoreExecutors.directExecutor());
  }

  @Override
  protected void startUp()
      throws Exception {
    this.server.start();
  }

  @Override
  protected void shutDown()
      throws Exception {
    this.server.stop();
  }

  public HealthCheck getHealthCheck() { return this.healthCheck; }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("url", format("http://localhost:%d/%s", this.port, this.path))
                      .toString();
  }
}
