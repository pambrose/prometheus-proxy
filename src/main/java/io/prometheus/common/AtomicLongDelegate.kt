package io.prometheus.common

import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KProperty

class AtomicLongDelegate {
    private val atomicVal = AtomicLong()

    operator fun getValue(thisRef: Any?, property: KProperty<*>): Long {
        return atomicVal.get()
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) {
        atomicVal.set(value)
    }
}
