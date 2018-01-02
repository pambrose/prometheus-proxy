package io.prometheus.dsl

import io.grpc.Attributes
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.internal.AbstractManagedChannelImplBuilder
import io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.StreamObserver
import io.prometheus.delegate.singleAssign

object GrpcDsl {
    fun channel(inProcessServerName: String = "",
                hostName: String = "",
                port: Int = -1,
                block: AbstractManagedChannelImplBuilder<*>.() -> Unit): ManagedChannel {
        val builder =
                if (inProcessServerName.isEmpty())
                    NettyChannelBuilder.forAddress(hostName, port)
                else
                    InProcessChannelBuilder.forName(inProcessServerName)

        return with(builder) {
            block(this)
            build()
        }
    }

    fun server(inProcessServerName: String = "", port: Int = -1, block: ServerBuilder<*>.() -> Unit): Server {
        val builder =
                if (inProcessServerName.isEmpty())
                    ServerBuilder.forPort(port)
                else
                    InProcessServerBuilder.forName(inProcessServerName)

        return with(builder) {
            block(this)
            build()
        }
    }

    fun attributes(block: Attributes.Builder.() -> Unit): Attributes {
        return with(Attributes.newBuilder()) {
            block(this)
            build()
        }
    }

    fun <T> newStreamObserver(init: StreamObserverHelper<T>.() -> Unit): StreamObserver<T> {
        val observer = StreamObserverHelper<T>()
        observer.init()
        return observer
    }

    class StreamObserverHelper<T> : StreamObserver<T> {
        private var onNextBlock: ((T) -> Unit)? by singleAssign()
        private var onErrorBlock: ((Throwable) -> Unit)? by singleAssign()
        private var completedBlock: (() -> Unit)? by singleAssign()

        override fun onNext(response: T) {
            onNextBlock?.invoke(response)
        }

        override fun onError(t: Throwable) {
            onErrorBlock?.invoke(t)
        }

        override fun onCompleted() {
            completedBlock?.invoke()
        }

        fun onNext(block: (T) -> Unit) {
            onNextBlock = block
        }

        fun onError(block: (Throwable) -> Unit) {
            onErrorBlock = block
        }

        fun onCompleted(block: () -> Unit) {
            completedBlock = block
        }
    }

}