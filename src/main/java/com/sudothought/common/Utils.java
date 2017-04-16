package com.sudothought.common;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.ThreadFactory;

public class Utils {

  public static ThreadFactory newInstrumentedThreadFactory(final String name,
                                                           final String help,
                                                           final boolean daemon) {
    final ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat(name + "-%d")
                                                                  .setDaemon(daemon)
                                                                  .build();
    return new InstrumentedThreadFactory(threadFactory, name, help);
  }
}
