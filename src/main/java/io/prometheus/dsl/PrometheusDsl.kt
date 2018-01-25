/*
 * Copyright © 2018 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.dsl

import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Summary

object PrometheusDsl {
    fun counter(builder: Counter.Builder.() -> Unit) =
            Counter.build()
                    .run {
                        builder(this)
                        register()
                    }

    fun summary(builder: Summary.Builder.() -> Unit) =
            Summary.build()
                    .run {
                        builder(this)
                        register()
                    }

    fun gauge(builder: Gauge.Builder.() -> Unit) =
            Gauge.build()
                    .run {
                        builder(this)
                        register()
                    }
}