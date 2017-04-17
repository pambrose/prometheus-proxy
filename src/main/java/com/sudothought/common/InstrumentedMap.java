package com.sudothought.common;

import com.google.common.base.Preconditions;
import com.google.common.collect.ForwardingMap;
import io.prometheus.client.Gauge;

import java.util.Map;

public class InstrumentedMap<K, V>
    extends ForwardingMap<K, V> {

  private final Map<K, V> delegate;
  private final Gauge     gauge;

  public InstrumentedMap(final Map<K, V> delegate, final Gauge gauge) {
    this.delegate = Preconditions.checkNotNull(delegate);
    this.gauge = Preconditions.checkNotNull(gauge);
  }

  @Override
  protected Map<K, V> delegate() { return this.delegate; }

  @Override
  public V remove(Object object) {
    final V retval = super.remove(object);
    if (retval != null)
      this.gauge.dec();
    return retval;
  }

  @Override
  public void clear() {
    super.clear();
    this.gauge.clear();
  }

  @Override
  public V put(K key, V value) {
    final V retval = super.put(key, value);
    this.gauge.inc();
    return retval;
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> map) {
    super.putAll(map);
    this.gauge.inc(map.size());
  }
}
