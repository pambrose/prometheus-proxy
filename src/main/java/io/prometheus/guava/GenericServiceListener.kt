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
package io.prometheus.guava

import com.google.common.util.concurrent.Service
import io.prometheus.dsl.GuavaDsl.serviceListener
import mu.KLogger

fun genericServiceListener(service: Service, logger: KLogger) =
        serviceListener {
            starting { logger.info { "Starting $service" } }
            running { logger.info { "Running $service" } }
            stopping { logger.info { "Stopping $service" } }
            terminated { logger.info { "Terminated $service" } }
            failed { from, t -> logger.error(t) { "Failed on $from $service" } }
        }
