/*
 * Copyright © 2026 Paul Ambrose
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

package io.prometheus.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

// Runs [block] and returns the events that [T]'s logger emitted meanwhile. [level], when given, overrides that logger's
// level for the duration -- needed to see DEBUG events under the default INFO configuration. The appender and the
// previous level are restored even when [block] throws. Inline, so [block] may suspend when the caller does.
internal inline fun <reified T : Any> captureLogs(
  level: Level? = null,
  block: () -> Unit,
): List<ILoggingEvent> {
  val logbackLogger = LoggerFactory.getLogger(T::class.java) as Logger
  val previousLevel = logbackLogger.level
  val appender = ListAppender<ILoggingEvent>().apply { start() }
  level?.let { logbackLogger.level = it }
  logbackLogger.addAppender(appender)
  try {
    block()
  } finally {
    logbackLogger.detachAppender(appender)
    appender.stop()
    logbackLogger.level = previousLevel
  }
  return appender.list.toList()
}
