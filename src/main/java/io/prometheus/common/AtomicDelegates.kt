package io.prometheus.common

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.*
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

object AtomicDelegates {
    fun <T : Any> notNullReference(initValue: T? = null): AtomicReferenceReadWriteProperty<Any?, T> = NotNullAtomicReferenceDelegate(initValue)
    fun <T : Any?> nullableReference(initValue: T? = null): AtomicNullableReadWriteProperty<Any?, T> = NullableAtomicReferenceDelegate(initValue)
    fun boolean(initValue: Boolean = false): AtomicReadWriteProperty<Any?, Boolean> = AtomicBooleanDelegate(initValue)
    fun integer(initValue: Int = 0): AtomicIntReadWriteProperty<Any?> = AtomicIntDelegate(initValue)
    fun long(initValue: Long = 0): AtomicLongReadWriteProperty<Any?> = AtomicLongDelegate(initValue)
}

interface AtomicReadWriteProperty<in R, T> : ReadWriteProperty<R, T> {
    fun lazySet(newValue: T)
    fun getAndSet(newValue: T): T
    fun compareAndSet(expect: T, update: T): Boolean
    fun weakCompareAndSet(expect: T, update: T): Boolean
}

interface AtomicReferenceReadWriteProperty<in R, T> : AtomicReadWriteProperty<R, T> {
    fun getAndUpdate(updateFunction: UnaryOperator<T>): T
    fun updateAndGet(updateFunction: UnaryOperator<T>): T
    fun getAndAccumulate(x: T, accumulatorFunction: BinaryOperator<T>): T
    fun accumulateAndGet(x: T, accumulatorFunction: BinaryOperator<T>): T
}

interface AtomicNullableReadWriteProperty<in R, T : Any?> {
    operator fun getValue(thisRef: R, property: KProperty<*>): T?
    operator fun setValue(thisRef: R, property: KProperty<*>, value: T)

    fun lazySet(newValue: T)
    fun getAndSet(newValue: T): T
    fun compareAndSet(expect: T, update: T): Boolean
    fun weakCompareAndSet(expect: T, update: T): Boolean

    fun getAndUpdate(updateFunction: UnaryOperator<T>): T
    fun updateAndGet(updateFunction: UnaryOperator<T>): T
    fun getAndAccumulate(x: T, accumulatorFunction: BinaryOperator<T>): T
    fun accumulateAndGet(x: T, accumulatorFunction: BinaryOperator<T>): T
}

interface AtomicNumberReadWriteProperty<in R, T> : AtomicReadWriteProperty<R, T> {
    fun getAndIncrement(): T
    fun getAndDecrement(): T
    fun getAndAdd(delta: T): T
    fun incrementAndGet(): T
    fun decrementAndGet(): T
    fun addAndGet(delta: T): T
}

interface AtomicIntReadWriteProperty<in R> : AtomicNumberReadWriteProperty<R, Int> {
    fun getAndUpdate(updateFunction: IntUnaryOperator): Int
    fun updateAndGet(updateFunction: IntUnaryOperator): Int
    fun getAndAccumulate(x: Int, accumulatorFunction: IntBinaryOperator): Int
    fun accumulateAndGet(x: Int, accumulatorFunction: IntBinaryOperator): Int
}

interface AtomicLongReadWriteProperty<in R> : AtomicNumberReadWriteProperty<R, Long> {
    fun getAndUpdate(updateFunction: LongUnaryOperator): Long
    fun updateAndGet(updateFunction: LongUnaryOperator): Long
    fun getAndAccumulate(x: Long, accumulatorFunction: LongBinaryOperator): Long
    fun accumulateAndGet(x: Long, accumulatorFunction: LongBinaryOperator): Long
}

private class NotNullAtomicReferenceDelegate<T : Any>(initValue: T? = null) : AtomicReferenceReadWriteProperty<Any?, T> {
    private val atomicVal = AtomicReference<T>(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>) = atomicVal.get() ?: throw IllegalStateException("Property ${property.name} should be initialized before get.")
    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = atomicVal.set(value)

    override fun lazySet(newValue: T) = atomicVal.lazySet(newValue)
    override fun getAndSet(newValue: T) = atomicVal.getAndSet(newValue)
    override fun compareAndSet(expect: T, update: T) = atomicVal.compareAndSet(expect, update)
    override fun weakCompareAndSet(expect: T, update: T) = atomicVal.weakCompareAndSet(expect, update)

    override fun getAndUpdate(updateFunction: UnaryOperator<T>) = atomicVal.getAndUpdate(updateFunction)
    override fun updateAndGet(updateFunction: UnaryOperator<T>) = atomicVal.updateAndGet(updateFunction)
    override fun getAndAccumulate(x: T, accumulatorFunction: BinaryOperator<T>) = atomicVal.getAndAccumulate(x, accumulatorFunction)
    override fun accumulateAndGet(x: T, accumulatorFunction: BinaryOperator<T>) = atomicVal.accumulateAndGet(x, accumulatorFunction)
}

private class NullableAtomicReferenceDelegate<T : Any?>(initValue: T? = null) : AtomicNullableReadWriteProperty<Any?, T> {
    private val atomicVal = AtomicReference<T>(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>) = atomicVal.get()
    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = atomicVal.set(value)

    override fun lazySet(newValue: T) = atomicVal.lazySet(newValue)
    override fun getAndSet(newValue: T) = atomicVal.getAndSet(newValue)
    override fun compareAndSet(expect: T, update: T) = atomicVal.compareAndSet(expect, update)
    override fun weakCompareAndSet(expect: T, update: T) = atomicVal.weakCompareAndSet(expect, update)

    override fun getAndUpdate(updateFunction: UnaryOperator<T>) = atomicVal.getAndUpdate(updateFunction)
    override fun updateAndGet(updateFunction: UnaryOperator<T>) = atomicVal.updateAndGet(updateFunction)
    override fun getAndAccumulate(x: T, accumulatorFunction: BinaryOperator<T>) = atomicVal.getAndAccumulate(x, accumulatorFunction)
    override fun accumulateAndGet(x: T, accumulatorFunction: BinaryOperator<T>) = atomicVal.accumulateAndGet(x, accumulatorFunction)
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

private class AtomicIntDelegate(initValue: Int) : AtomicIntReadWriteProperty<Any?> {
    private val atomicVal = AtomicInteger(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>) = atomicVal.get()
    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = atomicVal.set(value)

    override fun lazySet(newValue: Int) = atomicVal.lazySet(newValue)
    override fun getAndSet(newValue: Int) = atomicVal.getAndSet(newValue)
    override fun compareAndSet(expect: Int, update: Int) = atomicVal.compareAndSet(expect, update)
    override fun weakCompareAndSet(expect: Int, update: Int) = atomicVal.weakCompareAndSet(expect, update)

    override fun getAndIncrement() = atomicVal.getAndIncrement()
    override fun getAndDecrement() = atomicVal.getAndDecrement()
    override fun getAndAdd(delta: Int) = atomicVal.getAndAdd(delta)
    override fun incrementAndGet() = atomicVal.incrementAndGet()
    override fun decrementAndGet() = atomicVal.decrementAndGet()
    override fun addAndGet(delta: Int) = atomicVal.addAndGet(delta)

    override fun getAndUpdate(updateFunction: IntUnaryOperator) = atomicVal.getAndUpdate(updateFunction)
    override fun updateAndGet(updateFunction: IntUnaryOperator) = atomicVal.updateAndGet(updateFunction)
    override fun getAndAccumulate(x: Int, accumulatorFunction: IntBinaryOperator) = atomicVal.getAndAccumulate(x, accumulatorFunction)
    override fun accumulateAndGet(x: Int, accumulatorFunction: IntBinaryOperator) = atomicVal.accumulateAndGet(x, accumulatorFunction)
}

private class AtomicLongDelegate(initValue: Long) : AtomicLongReadWriteProperty<Any?> {
    private val atomicVal = AtomicLong(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>): Long = atomicVal.get()
    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) = atomicVal.set(value)

    override fun lazySet(newValue: Long) = atomicVal.lazySet(newValue)
    override fun getAndSet(newValue: Long) = atomicVal.getAndSet(newValue)
    override fun compareAndSet(expect: Long, update: Long) = atomicVal.compareAndSet(expect, update)
    override fun weakCompareAndSet(expect: Long, update: Long) = atomicVal.weakCompareAndSet(expect, update)

    override fun getAndIncrement() = atomicVal.getAndIncrement()
    override fun getAndDecrement() = atomicVal.getAndDecrement()
    override fun getAndAdd(delta: Long) = atomicVal.getAndAdd(delta)
    override fun incrementAndGet() = atomicVal.incrementAndGet()
    override fun decrementAndGet() = atomicVal.decrementAndGet()
    override fun addAndGet(delta: Long) = atomicVal.addAndGet(delta)

    override fun getAndUpdate(updateFunction: LongUnaryOperator) = atomicVal.getAndUpdate(updateFunction)
    override fun updateAndGet(updateFunction: LongUnaryOperator) = atomicVal.updateAndGet(updateFunction)
    override fun getAndAccumulate(x: Long, accumulatorFunction: LongBinaryOperator) = atomicVal.getAndAccumulate(x, accumulatorFunction)
    override fun accumulateAndGet(x: Long, accumulatorFunction: LongBinaryOperator) = atomicVal.accumulateAndGet(x, accumulatorFunction)
}