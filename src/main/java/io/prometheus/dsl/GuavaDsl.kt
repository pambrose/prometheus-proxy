package io.prometheus.dsl

import com.google.common.base.MoreObjects
import com.google.common.util.concurrent.Service
import com.google.common.util.concurrent.ServiceManager
import io.prometheus.delegate.singleAssign

private typealias NoArgs = () -> Unit

object GuavaDsl {

    fun Any.toStringElements(block: MoreObjects.ToStringHelper.() -> Unit): String {
        return with(MoreObjects.toStringHelper(this)) {
            block(this)
            toString()
        }
    }

    fun newServiceManagerListener(init: ServiceManagerListenerHelper.() -> Unit): ServiceManager.Listener {
        val listener = ServiceManagerListenerHelper()
        listener.init()
        return listener
    }

    class ServiceManagerListenerHelper : ServiceManager.Listener() {
        private var healthyBlock: NoArgs? by singleAssign()
        private var stoppedBlock: NoArgs? by singleAssign()
        private var failureBlock: ((service: Service) -> Unit)? by singleAssign()

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

        fun healthy(block: NoArgs) {
            healthyBlock = block
        }

        fun stopped(block: NoArgs) {
            stoppedBlock = block
        }

        fun failure(block: (Service) -> Unit) {
            failureBlock = block
        }
    }

    fun newServiceListener(init: ServiceListenerHelper.() -> Unit): Service.Listener {
        val listener = ServiceListenerHelper()
        listener.init()
        return listener
    }

    class ServiceListenerHelper : Service.Listener() {
        private var startingBlock: NoArgs? by singleAssign()
        private var runningBlock: NoArgs? by singleAssign()
        private var stoppingBlock: ((from: Service.State) -> Unit)? by singleAssign()
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

        fun starting(block: NoArgs?) {
            startingBlock = block
        }

        fun running(block: NoArgs) {
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

