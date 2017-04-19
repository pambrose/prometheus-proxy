package com.sudothought.common;

import com.google.common.collect.Lists;
import io.prometheus.client.Collector;

import java.util.List;

public class SamplerGauge
    extends Collector {

  private static final List<String> EMPTY_LIST = Lists.newArrayList();

  private final String           name;
  private final String           help;
  private final SamplerGaugeData samplerGaugeData;

  public SamplerGauge(final String name, final String help, final SamplerGaugeData samplerGaugeData) {
    this.name = name;
    this.help = help;
    this.samplerGaugeData = samplerGaugeData;
  }

  @Override
  public List<MetricFamilySamples> collect() {
    final MetricFamilySamples.Sample sample = new MetricFamilySamples.Sample(this.name,
                                                                             EMPTY_LIST,
                                                                             EMPTY_LIST,
                                                                             this.samplerGaugeData.value());
    return Lists.newArrayList(new MetricFamilySamples(this.name,
                                                      Type.GAUGE,
                                                      this.help,
                                                      Lists.newArrayList(sample)));
  }
}
