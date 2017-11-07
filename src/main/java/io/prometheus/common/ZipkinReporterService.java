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

import brave.Tracer;
import brave.Tracing;
import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.TracerAdapter;
import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.MoreExecutors;
import zipkin.Span;
import zipkin.reporter.AsyncReporter;
import zipkin.reporter.Sender;
import zipkin.reporter.okhttp3.OkHttpSender;

import java.io.IOException;

public class ZipkinReporterService
    extends AbstractIdleService {

  private final String              url;
  private final String              serviceName;
  private final Sender              sender;
  private final AsyncReporter<Span> reporter;
  private final Brave               brave;

  public ZipkinReporterService(final String url, final String serviceName) {
    this.url = url;
    this.serviceName = serviceName;
    this.sender = OkHttpSender.create(this.url);
    this.reporter = AsyncReporter.builder(this.sender).build();
    this.brave = TracerAdapter.newBrave(this.newTracer(this.serviceName));

    this.addListener(new GenericServiceListener(this), MoreExecutors.directExecutor());
  }

  public Tracer newTracer(final String serviceName) {
    return Tracing.newBuilder()
                  .localServiceName(serviceName)
                  .reporter(this.reporter)
                  .build()
                  .tracer();
  }

  @Override
  protected void startUp() {
    // Empty
  }

  @Override
  protected void shutDown()
      throws IOException {
    this.sender.close();
    this.reporter.close();
  }

  public Brave getBrave() { return this.brave; }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("serviceName", serviceName)
                      .add("url", url)
                      .toString();
  }
}
