package io.prometheus.common;

import com.codahale.metrics.servlets.HealthCheckServlet;
import com.codahale.metrics.servlets.PingServlet;
import com.codahale.metrics.servlets.ThreadDumpServlet;
import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.MoreExecutors;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;

public class AdminService
    extends AbstractIdleService {

  private final int    port;
  private final String pingPath;
  private final String healthCheckPath;
  private final String threadDumpPath;
  private final Server server;

  public AdminService(final GenericService service,
                      final int port,
                      final String pingPath,
                      final String healthCheckPath,
                      final String threadDumpPath) {
    this.port = port;
    this.pingPath = pingPath;
    this.healthCheckPath = healthCheckPath;
    this.threadDumpPath = threadDumpPath;
    this.server = new Server(this.port);

    final ServletContextHandler context = new ServletContextHandler();
    context.setContextPath("/");
    this.server.setHandler(context);

    if (!isNullOrEmpty(this.pingPath))
      context.addServlet(new ServletHolder(new PingServlet()), "/" + this.pingPath);
    if (!isNullOrEmpty(this.healthCheckPath))
      context.addServlet(new ServletHolder(new HealthCheckServlet(service.getHealthCheckRegistry())),
                         "/" + this.healthCheckPath);
    if (!isNullOrEmpty(this.threadDumpPath))
      context.addServlet(new ServletHolder(new ThreadDumpServlet()),
                         "/" + this.threadDumpPath);

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

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("ping", format(":%d /%s", this.port, this.pingPath))
                      .add("healthcheck", format(":%d /%s", this.port, this.healthCheckPath))
                      .add("threaddump", format(":%d /%s", this.port, this.threadDumpPath))
                      .toString();
  }
}
