/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.prometheus.common

import io.prometheus.client.Collector

class SamplerGaugeCollector(private val name: String,
                            private val help: String,
                            private val labelNames: List<String> = emptyList(),
                            private val labelValues: List<String> = emptyList(),
                            private val data: () -> Double) :
        Collector() {

    init {
        register<Collector>()
    }

    override fun collect(): List<MetricFamilySamples> {
        val sample = MetricFamilySamples.Sample(name,
                                                labelNames,
                                                labelValues,
                                                data())
        return listOf(MetricFamilySamples(name, Type.GAUGE, help, listOf(sample)))
    }
}
