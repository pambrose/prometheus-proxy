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

package io.prometheus.common

import com.google.common.collect.Lists
import io.prometheus.client.Collector

class SamplerGauge(private val name: String,
                   private val help: String,
                   private val samplerGaugeData: SamplerGaugeData) : Collector() {

    override fun collect(): List<Collector.MetricFamilySamples> {
        val sample = MetricFamilySamples.Sample(this.name,
                                                emptyList(),
                                                emptyList(),
                                                this.samplerGaugeData.value())
        return Lists.newArrayList(Collector.MetricFamilySamples(this.name,
                                                                Collector.Type.GAUGE,
                                                                this.help,
                                                                Lists.newArrayList<MetricFamilySamples.Sample>(sample)))
    }
}
