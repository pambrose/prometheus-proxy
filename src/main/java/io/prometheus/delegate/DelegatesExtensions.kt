/*
 * Copyright © 2018 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.delegate

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

object DelegatesExtensions {

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
}


