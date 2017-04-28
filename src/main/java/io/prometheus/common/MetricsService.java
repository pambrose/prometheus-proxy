package io.prometheus.common;

import com.google.common.util.concurrent.AbstractIdleService;
import io.prometheus.client.exporter.MetricsServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static java.lang.String.format;

public class MetricsService
    extends AbstractIdleService {

  private static final Logger logger = LoggerFactory.getLogger(MetricsService.class);

  private final int    port;
  private final String path;
  private final Server server;

  public MetricsService(final int port, final String path) {
    this.port = port;
    this.path = path;
    this.server = new Server(this.port);
  }

  @Override
  protected void startUp()
      throws IOException {
    final ServletContextHandler context = new ServletContextHandler();
    context.setContextPath("/");
    this.server.setHandler(context);
    context.addServlet(new ServletHolder(new MetricsServlet()), "/" + this.path);
    try {
      this.server.start();
      final String url = this.url();
      logger.info("Started metrics server started at {}", url);
    }
    catch (Exception e) {
      logger.error("Unsuccessful starting server", e);
      throw new IOException(e.getMessage());
    }
  }

  @Override
  protected void shutDown()
      throws Exception {
    this.server.stop();
  }

  public String url() { return format("http://localhost:%d/%s", this.port, this.path); }

  public int getPort() { return this.port; }

  public String getPath() { return this.path; }
}
