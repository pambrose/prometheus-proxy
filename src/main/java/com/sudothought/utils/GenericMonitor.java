package com.sudothought.utils;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.Monitor;

import java.util.concurrent.TimeUnit;

public abstract class GenericMonitor {

  private final Monitor monitor = new Monitor();

  private final Monitor.Guard trueValueGuard =
      new Monitor.Guard(this.getMonitor()) {
        @Override
        public boolean isSatisfied() { return isMonitorSatisfied(); }
      };

  private final Monitor.Guard falseValueGuard =
      new Monitor.Guard(this.getMonitor()) {
        @Override
        public boolean isSatisfied() { return !isMonitorSatisfied(); }
      };

  protected Monitor getMonitor() { return this.monitor; }

  public abstract boolean isMonitorSatisfied();

  public void waitUntilTrue() {
    try {
      this.getMonitor().enterWhenUninterruptibly(this.trueValueGuard);
    }
    finally {
      this.getMonitor().leave();
    }
  }

  public void waitUntilTrueWithInterruption()
      throws InterruptedException {
    try {
      this.getMonitor().enterWhen(this.trueValueGuard);
    }
    finally {
      if (this.getMonitor().isOccupiedByCurrentThread())
        this.getMonitor().leave();
    }
  }

  public boolean waitUntilTrueSecs(final long waitTimeSecs) {
    return this.waitUntilTrue(waitTimeSecs, TimeUnit.SECONDS);
  }

  public boolean waitUntilTrueWithInterruptionSecs(final long waitTimeSecs)
      throws InterruptedException {
    return this.waitUntilTrueWithInterruption(waitTimeSecs, TimeUnit.SECONDS);
  }

  public boolean waitUntilTrue(final long waitTime, final TimeUnit timeUnit) {
    boolean satisfied = false;
    try {
      satisfied = this.getMonitor().enterWhenUninterruptibly(this.trueValueGuard, waitTime, timeUnit);
    }
    finally {
      if (satisfied)
        this.getMonitor().leave();
    }
    return satisfied;
  }

  public boolean waitUntilTrueWithInterruption(final long waitTime, final TimeUnit timeUnit)
      throws InterruptedException {
    boolean satisfied = false;
    try {
      satisfied = this.getMonitor().enterWhen(this.trueValueGuard, waitTime, timeUnit);
    }
    finally {
      if (satisfied)
        this.getMonitor().leave();
    }
    return satisfied;
  }

  public void waitUntilFalse() {
    try {
      this.getMonitor().enterWhenUninterruptibly(this.falseValueGuard);
    }
    finally {
      this.getMonitor().leave();
    }
  }

  public boolean waitUntilFalseSecs(final long waitTimeSecs) {
    return this.waitUntilFalse(waitTimeSecs, TimeUnit.SECONDS);
  }

  public boolean waitUntilFalse(final long waitTime, final TimeUnit timeUnit) {
    boolean satisfied = false;
    try {
      satisfied = this.getMonitor().enterWhenUninterruptibly(this.falseValueGuard, waitTime, timeUnit);
    }
    finally {
      if (satisfied)
        this.getMonitor().leave();
    }
    return satisfied;
  }

  public boolean waitUntilTrue(final long timeoutSecs, final MonitorAction monitorAction) {
    return this.waitUntilTrue(timeoutSecs, -1, monitorAction);
  }

  public boolean waitUntilTrue(final long timeoutSecs, final long maxWaitSecs, final MonitorAction monitorAction) {
    final Stopwatch sw = Stopwatch.createStarted();
    while (true) {
      if (this.waitUntilTrueSecs(timeoutSecs))
        return true;

      if (maxWaitSecs > 0 && sw.elapsed(TimeUnit.SECONDS) >= maxWaitSecs)
        return false;

      if (monitorAction != null) {
        final boolean continueToWait = monitorAction.execute();
        if (!continueToWait)
          return false;
      }
    }
  }

  public boolean waitUntilTrueWithInterruption(final long timeoutSecs, final MonitorAction monitorAction)
      throws InterruptedException {
    return this.waitUntilTrueWithInterruption(timeoutSecs, -1, monitorAction);
  }

  public boolean waitUntilTrueWithInterruption(final long timeoutSecs,
                                               final long maxWaitSecs,
                                               final MonitorAction monitorAction)
      throws InterruptedException {
    final Stopwatch sw = Stopwatch.createStarted();
    while (true) {
      if (this.waitUntilTrueWithInterruptionSecs(timeoutSecs))
        return true;

      if (maxWaitSecs > 0 && sw.elapsed(TimeUnit.SECONDS) >= maxWaitSecs)
        return false;

      if (monitorAction != null) {
        final boolean continueToWait = monitorAction.execute();
        if (!continueToWait)
          return false;
      }
    }
  }

  public boolean waitUntilFalse(final long timeoutSecs, final MonitorAction monitorAction) {
    return this.waitUntilFalse(timeoutSecs, -1, monitorAction);
  }

  public boolean waitUntilFalse(final long timeoutSecs, final long maxWaitSecs, final MonitorAction monitorAction) {
    final Stopwatch sw = Stopwatch.createStarted();
    while (true) {
      if (this.waitUntilFalseSecs(timeoutSecs))
        return true;

      if (maxWaitSecs > 0 && sw.elapsed(TimeUnit.SECONDS) >= maxWaitSecs)
        return false;

      if (monitorAction != null) {
        final boolean continueToWait = monitorAction.execute();
        if (!continueToWait)
          return false;
      }
    }
  }

  public boolean waitUntilSecs(final boolean value, final long waitTimeSecs) {
    return value ? this.waitUntilTrueSecs(waitTimeSecs) : this.waitUntilFalseSecs(waitTimeSecs);
  }

  public void waitUntil(final boolean value) {
    if (value)
      this.waitUntilTrue();
    else
      this.waitUntilFalse();
  }
}
