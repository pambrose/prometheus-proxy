package io.prometheus.common;

@FunctionalInterface
public interface SamplerGaugeData {
  double value();
}
