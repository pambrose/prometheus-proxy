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
 * Thrown when an embedded Agent can't be built from its settings — a configuration file or URL cannot be
 * loaded/parsed, the command-line options fail to parse or ask for `-v`/`-u`, or a setting is invalid — in place of
 * ending the process. It comes from:
 *
 * - [Agent.startAsyncAgent][io.prometheus.Agent.Companion.startAsyncAgent]: always the form that takes a `Config`, and
 *   the one that takes a config file, as [Agent.startSyncAgent][io.prometheus.Agent.Companion.startSyncAgent] does,
 *   when given `exitOnMissingConfig = false`;
 * - [EmbeddedAgentConfig.start][io.prometheus.agent.EmbeddedAgentConfig.start], which names a value
 *   [fromEnvironment][io.prometheus.agent.EmbeddedAgentConfig.Builder.fromEnvironment] read by its variable too;
 * - the [EmbeddedAgentConfig.Builder][io.prometheus.agent.EmbeddedAgentConfig.Builder] methods, as they're called, for
 *   a value they can tell is wrong: a blank path, a `scrapeSelf` port out of range, or a path's URL with no scheme, or
 *   with credentials as well as a username and password;
 * - the Ktor plugin, for a registry without a path, or a value in the app's config file of the wrong type.
 *
 * [EmbeddedAgentConfig.startIfConfigured][io.prometheus.agent.EmbeddedAgentConfig.startIfConfigured] catches it, logs
 * why, and returns null. The Proxy never throws it: its entry point exits instead.
 *
 * An invalid setting is one the agent can't use, from the config, a `-D` override, the command line, an environment
 * variable, or the builder: a value of the wrong type or out of range, an unresolved `${…}` substitution, a malformed
 * proxy endpoint, a duplicate `pathConfigs` or `filters` entry, a path with a character the proxy refuses
 * (whitespace, a control character, `?`, or `#`), a filter regex that doesn't compile, an HTTPS trust store that is
 * missing, unreadable, or malformed, or TLS material it can't use: a certificate chain, private key, or trust
 * collection whose file is missing, unreadable, or malformed, whose PEM can't be read, or that is given both as a file
 * and as PEM, or a certificate chain without its key, or a key without its chain.
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
