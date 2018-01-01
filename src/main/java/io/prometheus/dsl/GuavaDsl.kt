package io.prometheus.dsl

import com.google.common.base.MoreObjects

object GuavaDsl {
    fun Any.toStringElements(block: MoreObjects.ToStringHelper.() -> Unit): String {
        return with(MoreObjects.toStringHelper(this)) {
            block(this)
            toString()
        }
    }

}