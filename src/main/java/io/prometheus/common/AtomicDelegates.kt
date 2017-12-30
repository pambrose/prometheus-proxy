package io.prometheus.common

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

object AtomicDelegates {
    fun <T : Any> notNullReference(): ReadWriteProperty<Any?, T> = NotNullAtomicReferenceDelegate()

    fun <T : Any?> nullableReference(): NullableReadWriteProperty<Any?, T> = NullableAtomicReferenceDelegate()

    fun boolean(initValue: Boolean = false): ReadWriteProperty<Any?, Boolean> = AtomicBooleanDelegate(initValue)

    fun long(initValue: Long = 0): ReadWriteProperty<Any?, Long> = AtomicLongDelegate(initValue)

    fun integer(initValue: Int = 0): ReadWriteProperty<Any?, Int> = AtomicIntDelegate(initValue)
}

private class NotNullAtomicReferenceDelegate<T : Any> : ReadWriteProperty<Any?, T> {
    private val atomicVal = AtomicReference<T>()

    override operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return atomicVal.get() ?: throw IllegalStateException("Property ${property.name} should be initialized before get.")
    }

    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        atomicVal.set(value)
    }
}

interface NullableReadWriteProperty<in R, T : Any?> {
    operator fun getValue(thisRef: R, property: KProperty<*>): T?

    operator fun setValue(thisRef: R, property: KProperty<*>, value: T)
}

private class NullableAtomicReferenceDelegate<T : Any?> : NullableReadWriteProperty<Any?, T> {
    private val atomicVal = AtomicReference<T>()

    override operator fun getValue(thisRef: Any?, property: KProperty<*>): T? {
        return atomicVal.get()
    }

    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        atomicVal.set(value)
    }
}

private class AtomicBooleanDelegate(initValue: Boolean) : ReadWriteProperty<Any?, Boolean> {
    private val atomicVal = AtomicBoolean(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>): Boolean {
        return atomicVal.get()
    }

    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
        atomicVal.set(value)
    }
}

private class AtomicLongDelegate(initValue: Long) : ReadWriteProperty<Any?, Long> {
    private val atomicVal = AtomicLong(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>): Long {
        return atomicVal.get()
    }

    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) {
        atomicVal.set(value)
    }
}

private class AtomicIntDelegate(initValue: Int) : ReadWriteProperty<Any?, Int> {
    private val atomicVal = AtomicInteger(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>): Int {
        return atomicVal.get()
    }

    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
        atomicVal.set(value)
    }
}