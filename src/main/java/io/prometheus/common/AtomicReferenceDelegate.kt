package io.prometheus.common

import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KProperty

class AtomicReferenceDelegate<T> {
    private val value = AtomicReference<T>()

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T? {
        return this.value.get()
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T?) {
        this.value.set(value)
    }
}
