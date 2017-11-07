/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

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
