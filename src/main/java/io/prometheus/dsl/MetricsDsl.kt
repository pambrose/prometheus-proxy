package io.prometheus.dsl

import com.codahale.metrics.health.HealthCheck
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Summary

object MetricsDsl {
    fun counter(builder: Counter.Builder.() -> Unit): Counter {
        return with(Counter.build()) {
            builder(this)
            register()
        }
    }

    fun summary(builder: Summary.Builder.() -> Unit): Summary {
        return with(Summary.build()) {
            builder(this)
            register()
        }
    }

    fun gauge(builder: Gauge.Builder.() -> Unit): Gauge {
        return with(Gauge.build()) {
            builder(this)
            register()
        }
    }

    fun healthCheck(block: HealthCheck.() -> HealthCheck.Result): HealthCheck {
        return object : HealthCheck() {
            @Throws(Exception::class)
            override fun check(): Result {
                return block.invoke(this)
            }
        }
    }
}