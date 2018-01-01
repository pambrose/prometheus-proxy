package io.prometheus.dsl

import com.google.common.util.concurrent.ThreadFactoryBuilder
import java.util.concurrent.ThreadFactory

object ThreadDsl {
    fun threadFactory(block: ThreadFactoryBuilder.() -> Unit): ThreadFactory {
        return with(ThreadFactoryBuilder()) {
            block(this)
            build()
        }
    }
}