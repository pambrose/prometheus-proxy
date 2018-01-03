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
import io.prometheus.delegate.DelegatesExtensions.singleAssign

object GrpcDsl {
    fun channel(inProcessServerName: String = "",
                hostName: String = "",
                port: Int = -1,
                block: AbstractManagedChannelImplBuilder<*>.() -> Unit): ManagedChannel {
        return (if (inProcessServerName.isEmpty())
            NettyChannelBuilder.forAddress(hostName, port)
        else
            InProcessChannelBuilder.forName(inProcessServerName))
                .run {
                    block(this)
                    build()
                }
    }

    fun server(inProcessServerName: String = "", port: Int = -1, block: ServerBuilder<*>.() -> Unit): Server {
        return (if (inProcessServerName.isEmpty())
            ServerBuilder.forPort(port)
        else
            InProcessServerBuilder.forName(inProcessServerName))
                .run {
                    block(this)
                    build()
                }
    }

    fun attributes(block: Attributes.Builder.() -> Unit): Attributes {
        return Attributes.newBuilder()
                .run {
                    block(this)
                    build()
                }
    }

    fun <T> streamObserver(init: StreamObserverHelper<T>.() -> Unit): StreamObserver<T> {
        return StreamObserverHelper<T>().apply { init() }
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