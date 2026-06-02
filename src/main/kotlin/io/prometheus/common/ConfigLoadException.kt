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

/**
 * Thrown when a configuration file or URL cannot be loaded or parsed and the process was started
 * with `exitOnMissingConfig = false` (the embedded mode used by
 * [Agent.startAsyncAgent][io.prometheus.Agent.Companion.startAsyncAgent]).
 *
 * In standalone mode (`exitOnMissingConfig = true`) a config-load failure terminates the process
 * via `exitProcess(1)` instead. Embedders that host an Agent inside their own JVM should catch this
 * exception so a transient remote-config fetch failure or a malformed config file does not kill the
 * host application.
 *
 * @param message a human-readable description of which config source failed to load
 * @param cause the underlying failure (e.g. a parse error or `FileNotFoundException`), if any
 */
class ConfigLoadException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
