package io.prometheus.common

import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KProperty

class AtomicReferenceDelegate<T> {
    private val atomicVal = AtomicReference<T>()

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T? {
        return atomicVal.get()
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T?) {
        atomicVal.set(value)
    }
}
