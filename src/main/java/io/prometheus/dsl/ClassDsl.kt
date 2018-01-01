package io.prometheus.dsl

import com.google.common.base.MoreObjects

object ClassDsl {
    fun Any.toStringElements(block: MoreObjects.ToStringHelper.() -> Unit): String {
        return with(MoreObjects.toStringHelper(this)) {
            block(this)
            toString()
        }
    }

}