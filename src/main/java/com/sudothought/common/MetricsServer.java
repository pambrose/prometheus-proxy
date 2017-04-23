package com.sudothought.common;

import io.prometheus.client.exporter.MetricsServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static java.lang.String.format;

public class MetricsServer {

  private static final Logger logger = LoggerFactory.getLogger(MetricsServer.class);

  private final int    port;
  private final String path;
  private final Server server;

  public MetricsServer(final int port, final String path) {
    this.port = port;
    this.path = path;
    this.server = new Server(this.port);
  }

  public void start()
      throws IOException {
    final ServletContextHandler context = new ServletContextHandler();
    context.setContextPath("/");
    this.server.setHandler(context);
    context.addServlet(new ServletHolder(new MetricsServlet()), "/" + this.path);
    try {
      this.server.start();
      final String url = this.url();
      logger.info("Started metrics server at {}", url);
    }
    catch (Exception e) {
      logger.error("Starting server", e);
      throw new IOException(e.getMessage());
    }
  }

  public void stop() {
    try {
      this.server.stop();
    }
    catch (Exception e) {
      logger.error("Stopping server", e);
    }
  }

  public String url() { return format("http://localhost:%d/%s", this.port, this.path); }

  public int getPort() { return this.port; }

  public String getPath() { return this.path; }
}
