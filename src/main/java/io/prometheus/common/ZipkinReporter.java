package io.prometheus.common;

import brave.Tracer;
import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.TracerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin.Span;
import zipkin.reporter.AsyncReporter;
import zipkin.reporter.Sender;
import zipkin.reporter.okhttp3.OkHttpSender;

import java.io.IOException;

public class ZipkinReporter {

  private static final Logger logger = LoggerFactory.getLogger(ZipkinReporter.class);

  private final Sender              sender;
  private final AsyncReporter<Span> reporter;
  private final Brave               brave;

  public ZipkinReporter(final String url, final String serviceName) {
    this.sender = OkHttpSender.create(url);
    this.reporter = AsyncReporter.builder(this.sender).build();
    this.brave = TracerAdapter.newBrave(this.newTracer(serviceName));
  }

  public Tracer newTracer(final String serviceName) {
    return Tracer.newBuilder()
                 .localServiceName(serviceName)
                 .reporter(this.reporter)
                 .build();
  }

  public Brave getBrave() { return this.brave; }

  public void close() {
    try {
      this.sender.close();
    }
    catch (IOException e) {
      logger.warn("IOException", e);
    }

    this.reporter.close();
  }
}
