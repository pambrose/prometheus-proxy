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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.dsl

import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Summary

object PrometheusDsl {
    fun counter(block: Counter.Builder.() -> Unit): Counter =
        Counter.build()
            .run {
                block.invoke(this)
                register()
            }

    fun summary(block: Summary.Builder.() -> Unit): Summary =
        Summary.build()
            .run {
                block.invoke(this)
                register()
            }

    fun gauge(block: Gauge.Builder.() -> Unit): Gauge =
        Gauge.build()
            .run {
                block.invoke(this)
                register()
            }
}