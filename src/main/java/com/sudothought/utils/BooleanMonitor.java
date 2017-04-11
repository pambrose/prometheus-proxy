package com.sudothought.utils;

import com.google.common.base.MoreObjects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public class BooleanMonitor
    extends GenericMonitor {

  private static final Logger LOGGER = LoggerFactory.getLogger(BooleanMonitor.class);

  private final AtomicBoolean value = new AtomicBoolean(false);

  public BooleanMonitor(final boolean startValue) { this.set(startValue); }

  public static MonitorAction debug(final String msg) {
    return () -> {
      LOGGER.debug(msg);
      return true;
    };
  }

  public static MonitorAction info(final String msg) {
    return () -> {
      LOGGER.info(msg);
      return true;
    };
  }

  public static MonitorAction warn(final String msg) {
    return () -> {
      LOGGER.warn(msg);
      return true;
    };
  }

  public static MonitorAction error(final String msg) {
    return () -> {
      LOGGER.error(msg);
      return true;
    };
  }

  @Override
  public boolean isMonitorSatisfied() {
    return get();
  }

  public boolean get() { return this.value.get(); }

  public final void set(final boolean value) {
    this.getMonitor().enter();
    try {
      this.value.set(value);
    }
    finally {
      this.getMonitor().leave();
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("value", this.value)
                      .toString();
  }
}
