package io.prometheus.dsl

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler

object JettyDsl {
    fun server(port: Int, block: Server.() -> Unit): Server {
        return Server(port).apply { block(this) }
    }

    fun servletContextHandler(block: ServletContextHandler.() -> Unit): ServletContextHandler {
        return ServletContextHandler().apply { block(this) }
    }
}