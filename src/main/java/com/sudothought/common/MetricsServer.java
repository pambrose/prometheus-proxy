package com.sudothought.common;

import io.prometheus.client.exporter.MetricsServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

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
      logger.info("Started metrics server at {}", this.getUrl());
    }
    catch (Exception e) {
      e.printStackTrace();
      throw new IOException(e.getMessage());
    }
  }

  public void stop() {
    try {
      this.server.stop();
    }
    catch (Exception e) {
      e.printStackTrace();
    }
  }

  public String getUrl() {
    return String.format("http://localhost:%d/%s", this.port, this.path);
  }

  public int getPort() { return this.port; }

  public String getPath() { return this.path; }
}
