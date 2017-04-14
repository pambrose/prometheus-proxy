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
  private final Server server;

  public MetricsServer(int port) {
    this.port = port;
    this.server = new Server(this.port);
  }

  public void start()
      throws IOException {
    final ServletContextHandler context = new ServletContextHandler();
    context.setContextPath("/");
    this.server.setHandler(context);
    context.addServlet(new ServletHolder(new MetricsServlet()), "/metrics");
    try {
      this.server.start();
      logger.info("Started local proxy metrics server at http://localhost:{}/metrics", this.port);
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
}
