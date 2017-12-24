package io.prometheus.common

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KProperty

class AtomicBooleanDelegate(initValue: Boolean) {
    private val value = AtomicBoolean(initValue)

    operator fun getValue(thisRef: Any?, property: KProperty<*>): Boolean {
        return this.value.get()
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
        this.value.set(value)
    }
}
