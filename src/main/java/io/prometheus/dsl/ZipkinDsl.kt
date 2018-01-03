package io.prometheus.dsl

import brave.Tracing

object ZipkinDsl {
    fun tracing(block: Tracing.Builder.() -> Unit): Tracing {
        return Tracing.newBuilder()
                .run {
                    block(this)
                    build()
                }
    }
}