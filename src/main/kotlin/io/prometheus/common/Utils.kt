/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.util.*
import kotlin.system.exitProcess
import kotlin.text.Charsets.UTF_8

object Utils {
  internal fun getVersionDesc(asJson: Boolean = false): String = Proxy::class.versionDesc(asJson)

  internal class VersionValidator : IParameterValidator {
    override fun validate(
      name: String,
      value: String,
    ) {
      val console = JCommander().console
      console.println(getVersionDesc(false))
      exitProcess(0)
    }
  }

  // This eliminates an extra set of paren in when blocks and if/else stmts
  fun <T> lambda(block: T) = block

  fun Boolean.ifTrue(block: () -> Unit) {
    if (this) block()
  }

  fun String.toLowercase() = this.lowercase(Locale.getDefault())

  fun decodeParams(encodedQueryParams: String): String =
    if (encodedQueryParams.isNotBlank()) "?${URLDecoder.decode(encodedQueryParams, UTF_8.name())}" else ""

  fun LocalDateTime.toFullDateString(): String =
    "${abbrevDayOfWeek()} ${monthNumber.lpad(2)}/${dayOfMonth.lpad(2)}/${(year - 2000).lpad(2)} " +
      "${hour.lpad(2)}:${minute.lpad(2)}:${second.lpad(2)} PST"

  internal fun Int.lpad(
    width: Int,
    padChar: Char = '0',
  ): String = toString().padStart(width, padChar)

  internal fun Int.rpad(
    width: Int,
    padChar: Char = '0',
  ): String = toString().padEnd(width, padChar)

  internal fun String.capitalizeFirstChar(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

  internal fun String.defaultEmptyJsonObject() = if (isEmpty()) "{}" else this

  fun String.toJsonElement() = Json.parseToJsonElement(this)

  fun LocalDateTime.abbrevDayOfWeek(): String = dayOfWeek.name.lowercase().capitalizeFirstChar().substring(0, 3)
}
