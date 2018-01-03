package io.prometheus.dsl

import com.codahale.metrics.health.HealthCheck

object MetricsDsl {
    fun healthCheck(block: HealthCheck.() -> HealthCheck.Result): HealthCheck {
        return object : HealthCheck() {
            @Throws(Exception::class)
            override fun check(): Result {
                return block.invoke(this)
            }
        }
    }
}