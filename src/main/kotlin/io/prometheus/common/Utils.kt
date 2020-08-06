/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import com.beust.jcommander.IParameterValidator
import com.beust.jcommander.JCommander
import com.github.pambrose.common.util.Version.Companion.versionDesc
import io.prometheus.Proxy
import kotlin.system.exitProcess

internal fun getVersionDesc(asJson: Boolean = false): String = Proxy::class.versionDesc(asJson)

internal class VersionValidator : IParameterValidator {
  override fun validate(name: String, value: String) {
    val console = JCommander().console
    console.println(getVersionDesc(false))
    exitProcess(0)
  }
}