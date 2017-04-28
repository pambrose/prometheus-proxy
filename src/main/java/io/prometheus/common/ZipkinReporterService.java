package io.prometheus.common;

import brave.Tracer;
import brave.Tracing;
import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.TracerAdapter;
import com.google.common.util.concurrent.AbstractIdleService;
import zipkin.Span;
import zipkin.reporter.AsyncReporter;
import zipkin.reporter.Sender;
import zipkin.reporter.okhttp3.OkHttpSender;

import java.io.IOException;

public class ZipkinReporterService
    extends AbstractIdleService {

  private final Sender              sender;
  private final AsyncReporter<Span> reporter;
  private final Brave               brave;

  public ZipkinReporterService(final String url, final String serviceName) {
    this.sender = OkHttpSender.create(url);
    this.reporter = AsyncReporter.builder(this.sender).build();
    this.brave = TracerAdapter.newBrave(this.newTracer(serviceName));
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
}
