package io.prometheus.delegate

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Returns a property delegate for a read/write property that can be assigned only once. Trying to assign the property
 * a second time results in an exception.
 */
fun <T : Any?> singleAssign(): ReadWriteProperty<Any?, T?> = SingleAssignVar()

private class SingleAssignVar<T : Any?> : ReadWriteProperty<Any?, T?> {
    private var value: T? = null

    override fun getValue(thisRef: Any?, property: KProperty<*>): T? {
        return value
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T?) {
        if (this.value != null)
            throw IllegalStateException("Property ${property.name} cannot be assigned more than once.")
        this.value = value
    }
}

