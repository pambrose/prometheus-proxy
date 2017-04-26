package io.prometheus.common;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;

import java.util.concurrent.ThreadFactory;

import static java.lang.String.format;

public class InstrumentedThreadFactory
    implements ThreadFactory {

  private final ThreadFactory delegate;
  private final Counter       created;
  private final Gauge         running;
  private final Counter       terminated;

  public InstrumentedThreadFactory(final ThreadFactory delegate, final String name, final String help) {
    Preconditions.checkNotNull(name);
    Preconditions.checkNotNull(help);
    this.delegate = Preconditions.checkNotNull(delegate);
    this.created = Counter.build()
                          .name(format("%s_threads_created", name))
                          .help(format("%s threads created", help))
                          .register();
    this.running = Gauge.build()
                        .name(format("%s_threads_running", name))
                        .help(format("%s threads running", help))
                        .register();
    this.terminated = Counter.build()
                             .name(format("%s_threads_terminated", name))
                             .help(format("%s threads terminated", help))
                             .register();
  }

  public static ThreadFactory newInstrumentedThreadFactory(final String name,
                                                           final String help,
                                                           final boolean daemon) {
    final ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat(name + "-%d")
                                                                  .setDaemon(daemon)
                                                                  .build();
    return new InstrumentedThreadFactory(threadFactory, name, help);
  }

  @Override
  public Thread newThread(final Runnable runnable) {
    final Runnable wrappedRunnable = new InstrumentedRunnable(runnable);
    final Thread thread = this.delegate.newThread(wrappedRunnable);
    this.created.inc();
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
        terminated.inc();
      }
    }
  }

}
