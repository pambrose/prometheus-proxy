package io.prometheus.delegate

import java.util.concurrent.atomic.AtomicReference
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

object AtomicDelegates {
    fun <T : Any> notNullReference(initValue: T? = null): ReadWriteProperty<Any?, T> = NotNullAtomicReferenceDelegate(initValue)
    fun <T : Any?> nullableReference(initValue: T? = null): ReadWriteProperty<Any?, T> = NullableAtomicReferenceDelegate(initValue)
}


private class NotNullAtomicReferenceDelegate<T : Any>(initValue: T? = null) : ReadWriteProperty<Any?, T> {
    private val atomicVal = AtomicReference<T>(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>) = atomicVal.get() ?: throw IllegalStateException("Property ${property.name} should be initialized before get.")
    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = atomicVal.set(value)
}

private class NullableAtomicReferenceDelegate<T : Any?>(initValue: T? = null) : ReadWriteProperty<Any?, T> {
    private val atomicVal = AtomicReference<T>(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>): T = atomicVal.get()
    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = atomicVal.set(value)
}
