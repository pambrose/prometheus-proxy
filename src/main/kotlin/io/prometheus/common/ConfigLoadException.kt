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
 * Thrown when startup cannot complete — a configuration file or URL cannot be loaded/parsed, the
 * command-line options fail to parse, or a setting is invalid — and the process was started with
 * `exitOnMissingConfig = false` (the embedded mode used by
 * [Agent.startAsyncAgent][io.prometheus.Agent.Companion.startAsyncAgent]).
 *
 * An invalid setting is one the agent can't use, from the config, a `-D` override, the command line, or an
 * environment variable: a value of the wrong type or out of range, an unresolved `${…}` substitution, a
 * duplicate `pathConfigs` or `filters` entry, a filter regex that doesn't compile, or an HTTPS trust store or
 * TLS certificate file that is missing or malformed.
 *
 * In standalone mode (`exitOnMissingConfig = true`) such a failure terminates the process via
 * `exitProcess` instead. Embedders that host an Agent inside their own JVM should catch this
 * exception so a transient remote-config fetch failure, a malformed config file, or an invalid
 * setting does not kill the host application.
 *
 * A config URL can carry credentials (`http://user:pass@host/agent.conf`), so they are redacted from the
 * message and from every message in the cause chain. A cause whose message held them is replaced, along with
 * every cause above it, by a `ConfigLoadException` that carries the redacted message and the original's stack
 * trace.
 *
 * @param message a human-readable description of what failed to load or parse
 * @param cause the underlying failure (e.g. a parse error, `FileNotFoundException`, a JCommander
 *   `ParameterException`, or the `IllegalArgumentException` that rejected a setting), if any
 */
class ConfigLoadException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
