package io.prometheus.dsl

import org.eclipse.jetty.servlet.ServletContextHandler
import spark.Service

object SparkDsl {
    inline fun httpServer(block: Service.() -> Unit): Service {
        return Service.ignite().apply { block.invoke(this) }
    }

    inline fun servletContextHandler(block: ServletContextHandler.() -> Unit): ServletContextHandler {
        return ServletContextHandler().apply { block.invoke(this) }
    }
}
