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

import com.google.common.base.MoreObjects
import com.google.common.util.concurrent.Service
import com.google.common.util.concurrent.ServiceManager
import io.prometheus.delegate.DelegatesExtensions.singleAssign

object GuavaDsl {
    fun Any.toStringElements(block: MoreObjects.ToStringHelper.() -> Unit) =
            MoreObjects.toStringHelper(this)!!
                    .run {
                        block.invoke(this)
                        toString()
                    }

    fun serviceManager(services: List<Service>, block: ServiceManager.() -> Unit) =
            ServiceManager(services).apply { block.invoke(this) }

    fun serviceManagerListener(init: ServiceManagerListenerHelper.() -> Unit) =
            ServiceManagerListenerHelper().apply { init() }

    class ServiceManagerListenerHelper : ServiceManager.Listener() {
        private var healthyBlock: (() -> Unit)? by singleAssign()
        private var stoppedBlock: (() -> Unit)? by singleAssign()
        private var failureBlock: ((Service) -> Unit)? by singleAssign()

        override fun healthy() {
            super.healthy()
            healthyBlock?.invoke()
        }

        override fun stopped() {
            super.stopped()
            stoppedBlock?.invoke()
        }

        override fun failure(service: Service) {
            super.failure(service)
            failureBlock?.invoke(service)
        }

        fun healthy(block: () -> Unit) {
            healthyBlock = block
        }

        fun stopped(block: () -> Unit) {
            stoppedBlock = block
        }

        fun failure(block: (Service) -> Unit) {
            failureBlock = block
        }
    }

    fun serviceListener(init: ServiceListenerHelper.() -> Unit) =
            ServiceListenerHelper().apply { init() }

    class ServiceListenerHelper : Service.Listener() {
        private var startingBlock: (() -> Unit)? by singleAssign()
        private var runningBlock: (() -> Unit)? by singleAssign()
        private var stoppingBlock: ((Service.State) -> Unit)? by singleAssign()
        private var terminatedBlock: ((Service.State) -> Unit)? by singleAssign()
        private var failedBlock: ((Service.State, Throwable) -> Unit)? by singleAssign()

        override fun starting() {
            super.starting()
            startingBlock?.invoke()
        }

        override fun running() {
            super.running()
            runningBlock?.invoke()
        }

        override fun stopping(from: Service.State) {
            super.stopping(from)
            stoppingBlock?.invoke(from)
        }

        override fun terminated(from: Service.State) {
            super.terminated(from)
            terminatedBlock?.invoke(from)
        }

        override fun failed(from: Service.State, failure: Throwable) {
            super.failed(from, failure)
            failedBlock?.invoke(from, failure)
        }

        fun starting(block: (() -> Unit)?) {
            startingBlock = block
        }

        fun running(block: () -> Unit) {
            runningBlock = block
        }

        fun stopping(block: (Service.State) -> Unit) {
            stoppingBlock = block
        }

        fun terminated(block: (Service.State) -> Unit) {
            terminatedBlock = block
        }

        fun failed(block: (Service.State, Throwable) -> Unit) {
            failedBlock = block
        }
    }
}

