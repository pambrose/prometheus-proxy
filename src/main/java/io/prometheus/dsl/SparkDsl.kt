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

package io.prometheus.dsl

import org.eclipse.jetty.servlet.ServletContextHandler
import spark.Service

object SparkDsl {
    inline fun httpServer(block: Service.() -> Unit) =
            Service.ignite().apply { block.invoke(this) }!!

    inline fun servletContextHandler(block: ServletContextHandler.() -> Unit) =
            ServletContextHandler().apply { block.invoke(this) }
}
