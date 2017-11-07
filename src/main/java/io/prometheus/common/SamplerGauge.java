/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus.common;

import com.google.common.collect.Lists;
import io.prometheus.client.Collector;

import java.util.Collections;
import java.util.List;

public class SamplerGauge
    extends Collector {

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
                                                                             Collections.emptyList(),
                                                                             Collections.emptyList(),
                                                                             this.samplerGaugeData.value());
    return Lists.newArrayList(new MetricFamilySamples(this.name,
                                                      Type.GAUGE,
                                                      this.help,
                                                      Lists.newArrayList(sample)));
  }
}
