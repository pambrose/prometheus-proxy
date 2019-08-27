/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.delegate

import java.util.concurrent.atomic.AtomicReference
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

object AtomicDelegates {
    fun <T : Any> nonNullableReference(initValue: T? = null): ReadWriteProperty<Any?, T> =
        NotNullAtomicReferenceDelegate(initValue)

    fun <T : Any?> nullableReference(initValue: T? = null): ReadWriteProperty<Any?, T> =
        NullableAtomicReferenceDelegate(initValue)
}

private class NotNullAtomicReferenceDelegate<T : Any>(initValue: T? = null) : ReadWriteProperty<Any?, T> {
    private val atomicVal = AtomicReference<T>(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>) =
        atomicVal.get()
            ?: throw IllegalStateException("Property ${property.name} should be initialized before get.")

    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = atomicVal.set(value)
}

private class NullableAtomicReferenceDelegate<T : Any?>(initValue: T? = null) : ReadWriteProperty<Any?, T> {
    private val atomicVal = AtomicReference<T>(initValue)

    override operator fun getValue(thisRef: Any?, property: KProperty<*>): T = atomicVal.get()
    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = atomicVal.set(value)
}
