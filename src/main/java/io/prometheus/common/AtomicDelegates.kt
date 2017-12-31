package io.prometheus.common

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

object AtomicDelegates {
    fun <T : Any> notNullReference(initValue: T? = null): AtomicReadWriteProperty<Any?, T> = NotNullAtomicReferenceDelegate(initValue)
    fun <T : Any?> nullableReference(initValue: T? = null): AtomicNullableReadWriteProperty<Any?, T> = NullableAtomicReferenceDelegate(initValue)
    fun boolean(initValue: Boolean = false): AtomicReadWriteProperty<Any?, Boolean> = AtomicBooleanDelegate(initValue)
    fun long(initValue: Long = 0): AtomicReadWriteProperty<Any?, Long> = AtomicLongDelegate(initValue)
    fun integer(initValue: Int = 0): AtomicReadWriteProperty<Any?, Int> = AtomicIntDelegate(initValue)
}

interface AtomicReadWriteProperty<in R, T> : ReadWriteProperty<R, T> {
    fun lazySet(newValue: T)
    fun getAndSet(newValue: T): T
    fun compareAndSet(expect: T, update: T): Boolean
    fun weakCompareAndSet(expect: T, update: T): Boolean
}

interface AtomicNullableReadWriteProperty<in R, T : Any?> {
    operator fun getValue(thisRef: R, property: KProperty<*>): T?
    operator fun setValue(thisRef: R, property: KProperty<*>, value: T)

    fun lazySet(newValue: T)
    fun getAndSet(newValue: T): T
    fun compareAndSet(expect: T, update: T): Boolean
    fun weakCompareAndSet(expect: T, update: T): Boolean
}

private class NotNullAtomicReferenceDelegate<T : Any>(initValue: T? = null) : AtomicReadWriteProperty<Any?, T> {
    private val atomicVal = AtomicReference<T>(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>) = atomicVal.get() ?: throw IllegalStateException("Property ${property.name} should be initialized before get.")
    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = atomicVal.set(value)

    override fun lazySet(newValue: T) = atomicVal.lazySet(newValue)
    override fun getAndSet(newValue: T) = atomicVal.getAndSet(newValue)
    override fun compareAndSet(expect: T, update: T) = atomicVal.compareAndSet(expect, update)
    override fun weakCompareAndSet(expect: T, update: T) = atomicVal.weakCompareAndSet(expect, update)
}

private class NullableAtomicReferenceDelegate<T : Any?>(initValue: T? = null) : AtomicNullableReadWriteProperty<Any?, T> {
    private val atomicVal = AtomicReference<T>(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>) = atomicVal.get()
    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = atomicVal.set(value)

    override fun lazySet(newValue: T) = atomicVal.lazySet(newValue)
    override fun getAndSet(newValue: T) = atomicVal.getAndSet(newValue)
    override fun compareAndSet(expect: T, update: T) = atomicVal.compareAndSet(expect, update)
    override fun weakCompareAndSet(expect: T, update: T) = atomicVal.weakCompareAndSet(expect, update)
}

private class AtomicBooleanDelegate(initValue: Boolean) : AtomicReadWriteProperty<Any?, Boolean> {
    private val atomicVal = AtomicBoolean(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>) = atomicVal.get()
    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = atomicVal.set(value)

    override fun lazySet(newValue: Boolean) = atomicVal.lazySet(newValue)
    override fun getAndSet(newValue: Boolean) = atomicVal.getAndSet(newValue)
    override fun compareAndSet(expect: Boolean, update: Boolean) = atomicVal.compareAndSet(expect, update)
    override fun weakCompareAndSet(expect: Boolean, update: Boolean) = atomicVal.weakCompareAndSet(expect, update)
}

private class AtomicLongDelegate(initValue: Long) : AtomicReadWriteProperty<Any?, Long> {
    private val atomicVal = AtomicLong(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>): Long = atomicVal.get()
    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) = atomicVal.set(value)

    override fun lazySet(newValue: Long) = atomicVal.lazySet(newValue)
    override fun getAndSet(newValue: Long) = atomicVal.getAndSet(newValue)
    override fun compareAndSet(expect: Long, update: Long) = atomicVal.compareAndSet(expect, update)
    override fun weakCompareAndSet(expect: Long, update: Long) = atomicVal.weakCompareAndSet(expect, update)
}

private class AtomicIntDelegate(initValue: Int) : AtomicReadWriteProperty<Any?, Int> {

    private val atomicVal = AtomicInteger(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>) = atomicVal.get()
    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = atomicVal.set(value)

    override fun lazySet(newValue: Int) = atomicVal.lazySet(newValue)
    override fun getAndSet(newValue: Int) = atomicVal.getAndSet(newValue)
    override fun compareAndSet(expect: Int, update: Int) = atomicVal.compareAndSet(expect, update)
    override fun weakCompareAndSet(expect: Int, update: Int) = atomicVal.weakCompareAndSet(expect, update)
}