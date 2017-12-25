package io.prometheus.common

import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KProperty

class AtomicLongDelegate {
    private val value = AtomicLong()

    operator fun getValue(thisRef: Any?, property: KProperty<*>): Long {
        return this.value.get()
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) {
        this.value.set(value)
    }
}
