package com.sudothought.common;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ForwardingBlockingQueue;
import io.prometheus.client.Gauge;

import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class InstrumentedBlockingQueue<E>
    extends ForwardingBlockingQueue<E> {

  private final BlockingQueue<E> delegate;
  private final Gauge            gauge;

  public InstrumentedBlockingQueue(final BlockingQueue<E> delegate, final Gauge gauge) {
    this.delegate = Preconditions.checkNotNull(delegate);
    this.gauge = Preconditions.checkNotNull(gauge);
  }

  @Override
  protected BlockingQueue<E> delegate() {
    return this.delegate;
  }

  @Override
  public int drainTo(Collection<? super E> c, int maxElements) {
    final int retval = super.drainTo(c, maxElements);
    this.gauge.dec(retval);
    return retval;
  }

  @Override
  public int drainTo(Collection<? super E> c) {
    final int retval = super.drainTo(c);
    this.gauge.dec(retval);
    return retval;
  }

  @Override
  public boolean offer(E e, long timeout, TimeUnit unit)
      throws InterruptedException {
    final boolean retval = super.offer(e, timeout, unit);
    if (retval)
      this.gauge.inc();
    return retval;
  }

  @Override
  public E poll(long timeout, TimeUnit unit)
      throws InterruptedException {
    final E val = super.poll(timeout, unit);
    if (val != null)
      this.gauge.dec();
    return val;
  }

  @Override
  public void put(E e)
      throws InterruptedException {
    super.put(e);
    this.gauge.inc();
  }

  @Override
  public E take()
      throws InterruptedException {
    final E retval = super.take();
    this.gauge.dec();
    return retval;
  }

  @Override
  public boolean removeAll(Collection<?> collection) {
    return super.removeAll(collection);
  }

  @Override
  public boolean add(E element) {
    final boolean retval = super.add(element);
    if (retval)
      this.gauge.inc();
    return retval;
  }

  @Override
  public void clear() {
    super.clear();
    this.gauge.clear();
  }
}
