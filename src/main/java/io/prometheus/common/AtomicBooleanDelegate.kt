package io.prometheus.common

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KProperty

class AtomicBooleanDelegate(initValue: Boolean) {
    private val atomicVal = AtomicBoolean(initValue)

    operator fun getValue(thisRef: Any?, property: KProperty<*>): Boolean {
        return atomicVal.get()
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
        atomicVal.set(value)
    }
}
