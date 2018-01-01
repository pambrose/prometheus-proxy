package io.prometheus.dsl

import io.grpc.Attributes
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.internal.AbstractManagedChannelImplBuilder
import io.grpc.netty.NettyChannelBuilder

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
}