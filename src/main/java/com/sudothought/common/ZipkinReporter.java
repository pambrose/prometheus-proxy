package com.sudothought.common;

import brave.Tracer;
import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.TracerAdapter;
import zipkin.Span;
import zipkin.reporter.AsyncReporter;
import zipkin.reporter.Sender;
import zipkin.reporter.okhttp3.OkHttpSender;

import java.io.IOException;

public class ZipkinReporter {

  private final Sender              sender;
  private final AsyncReporter<Span> reporter;
  private final Tracer              tracer;
  private final Brave               brave;

  public ZipkinReporter(final String url, final String serviceName) {
    this.sender = OkHttpSender.create(url);
    this.reporter = AsyncReporter.builder(this.sender).build();
    this.tracer = Tracer.newBuilder()
                        .localServiceName(serviceName)
                        .reporter(this.reporter)
                        .build();
    this.brave = TracerAdapter.newBrave(this.tracer);
  }

  public Brave getBrave() { return this.brave; }

  public void close() {
    try {
      this.sender.close();
    }
    catch (IOException e) {
      e.printStackTrace();
    }
    this.reporter.close();
  }
}
