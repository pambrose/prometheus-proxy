package com.sudothought.common;

import com.google.common.base.Preconditions;
import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;

import java.util.concurrent.ThreadFactory;

public class InstrumentedThreadFactory
    implements ThreadFactory {

  private final ThreadFactory delegate;
  private final Summary       created;
  private final Gauge         running;
  private final Summary       terminated;

  public InstrumentedThreadFactory(final ThreadFactory delegate, final String name, final String help) {
    Preconditions.checkNotNull(name);
    Preconditions.checkNotNull(help);
    this.delegate = Preconditions.checkNotNull(delegate);
    this.created = Summary.build()
                          .name(String.format("%s_threads_created", name))
                          .help(String.format("%s threads created", help))
                          .register();
    this.running = Gauge.build()
                        .name(String.format("%s_threads_running", name))
                        .help(String.format("%s threads running", help))
                        .register();
    this.terminated = Summary.build()
                             .name(String.format("%s_threads_terminated", name))
                             .help(String.format("%s threads terminated", help))
                             .register();
  }

  @Override
  public Thread newThread(final Runnable runnable) {
    final Runnable wrappedRunnable = new InstrumentedRunnable(runnable);
    final Thread thread = this.delegate.newThread(wrappedRunnable);
    this.created.observe(1);
    return thread;
  }

  private class InstrumentedRunnable
      implements Runnable {

    private final Runnable runnable;

    private InstrumentedRunnable(Runnable runnable) {
      this.runnable = runnable;
    }

    @Override
    public void run() {
      running.inc();
      try {
        runnable.run();
      }
      finally {
        running.dec();
        terminated.observe(1);
      }
    }
  }

}
