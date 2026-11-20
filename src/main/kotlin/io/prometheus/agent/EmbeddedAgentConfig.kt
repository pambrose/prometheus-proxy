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

package io.prometheus.agent

import com.typesafe.config.Config
import com.typesafe.config.ConfigValueFactory
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.prometheus.Agent
import io.prometheus.common.ConfigLoadException
import io.prometheus.common.Utils.sanitizeUrl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An embedded agent's settings, built in code with typed methods rather than as a config file, and started with
 * [start]. It suits an app that holds the agent's settings itself, such as a token, the proxy's certificate, or the
 * credentials for its own `/metrics`: they're held in memory and never written to disk.
 *
 * From Java, build one with [builder]; from Kotlin, with [embeddedAgent]:
 *
 * ```kotlin
 * val agent =
 *   embeddedAgent {
 *     name("my-app-$version")
 *     proxy("proxy.example.com:50051")
 *     token(token)
 *     trustCertificatePem(certificatePem, authority = "prometheus-proxy")
 *     scrapeSelf(path = "my_app_metrics", port = 8080, username = user, password = password)
 *   }.start(logBanner = false)
 * ```
 *
 * It builds the same tree a config file holds, rooted at `agent`, so starting it behaves as
 * [Agent.startAsyncAgent][io.prometheus.Agent.Companion.startAsyncAgent] does with a `Config`: the built-in defaults
 * fill in what isn't set, the environment variables of single settings take precedence, and a setting the agent can't
 * use throws [ConfigLoadException]. [toConfig] gives the tree itself.
 */
class EmbeddedAgentConfig private constructor(
  private val config: Config,
) {
  /** The settings as the `Config` tree a config file would hold, rooted at `agent`. */
  fun toConfig(): Config = config

  /**
   * Starts an agent with these settings, as [Agent.startAsyncAgent][io.prometheus.Agent.Companion.startAsyncAgent]
   * does with a `Config`, and returns its handle once it has started.
   *
   * @param logBanner When `true` (default), logs the agent's ASCII banner and version on startup.
   * @throws ConfigLoadException when a setting is invalid.
   * @throws IllegalStateException if the agent fails to start.
   */
  @JvmOverloads
  fun start(logBanner: Boolean = true): EmbeddedAgentInfo = Agent.startAsyncAgent(config, logBanner)

  /**
   * The settings, [setting][Builder.setting]'s included, with the token, a password, and each PEM shown only as set or
   * not, and the credentials in a path's URL masked, so an app can log it.
   */
  override fun toString(): String = "EmbeddedAgentConfig(${shown(config.getObject("agent").unwrapped())})"

  /**
   * Builds an [EmbeddedAgentConfig]. Each method returns the builder, so calls chain in Java and read as statements in
   * Kotlin's [embeddedAgent] block; a method called twice keeps its last value, and [path] and [scrapeSelf] add a path
   * each time. Anything the builder has no method for, [setting] sets by its key. A value the builder can tell is
   * wrong, such as a port out of range, throws [ConfigLoadException], as [start] does for one the agent rejects.
   */
  class Builder internal constructor() {
    // The methods' settings, keyed by their path under agent, then the paths, then setting()'s, which come last in the
    // tree, so they take precedence.
    private val values = linkedMapOf<String, Any>()
    private val paths = mutableListOf<Map<String, String>>()
    private val settings = linkedMapOf<String, Any>()

    /** The agent's name, as the proxy's dashboard and logs show it (`agent.name`). */
    fun name(name: String): Builder = apply { values["name"] = name }

    /**
     * The proxy the agent connects to, as `host` or `host:port` (the port defaults to 50051), or several, in the
     * order the agent fails over through them (`agent.proxy.endpoints`).
     */
    fun proxy(vararg endpoints: String): Builder = apply { values["proxy.endpoints"] = endpoints.toList() }

    /** The token the agent presents to the proxy, which has to match the proxy's (`agent.agentToken`). */
    fun token(token: String): Builder = apply { values["agentToken"] = token }

    /**
     * The certificate the agent trusts the proxy's certificate by, as PEM text or its base64
     * (`agent.tls.trustCertCollectionPem`), which turns TLS on. [authority] is the name the proxy's certificate was
     * issued to, when it isn't the host the agent connects to (`agent.tls.overrideAuthority`).
     */
    @JvmOverloads
    fun trustCertificatePem(
      pem: String,
      authority: String? = null,
    ): Builder =
      apply {
        values["tls.trustCertCollectionPem"] = pem
        if (authority == null) values.remove("tls.overrideAuthority") else values["tls.overrideAuthority"] = authority
      }

    /**
     * The agent's own certificate chain and private key, as PEM text or its base64, for a proxy that requires mutual
     * TLS (`agent.tls.certChainPem` and `agent.tls.privateKeyPem`).
     */
    fun clientCertificatePem(
      certChainPem: String,
      privateKeyPem: String,
    ): Builder =
      apply {
        values["tls.certChainPem"] = certChainPem
        values["tls.privateKeyPem"] = privateKeyPem
      }

    /** Whether the agent registers its paths in consolidated mode, alongside other agents (`agent.consolidated`). */
    @JvmOverloads
    fun consolidated(consolidated: Boolean = true): Builder = apply { values["consolidated"] = consolidated }

    /**
     * Turns on the agent's own metrics endpoint, on [port], which is off unless asked for (`agent.metrics.enabled`
     * and `agent.metrics.port`).
     */
    fun agentMetrics(port: Int): Builder =
      apply {
        values["metrics.enabled"] = true
        values["metrics.port"] = port
      }

    /**
     * Has the agent scrape the app it's embedded in: its metrics at `http://127.0.0.1:<port>/<metricsPath>`, served to
     * Prometheus at the proxy's [path]. A [username] and [password] are sent as Basic Auth; they're percent-encoded
     * into the URL, which the agent leaves out of its logs. Both empty means none.
     */
    @JvmOverloads
    fun scrapeSelf(
      path: String,
      port: Int,
      username: String? = null,
      password: String? = null,
      metricsPath: String = "metrics",
      labels: Map<String, String> = emptyMap(),
    ): Builder {
      if (port !in 1..65535) invalid("The port for path $path must be from 1 to 65535: $port")
      return path(path, "http://127.0.0.1:$port/${metricsPath.removePrefix("/")}", username, password, labels)
    }

    /**
     * Has the agent scrape the target at [url], served to Prometheus at the proxy's [path]. A [username] and
     * [password] are sent as Basic Auth; they're percent-encoded into the URL, which then mustn't carry credentials of
     * its own. Both empty means none.
     */
    @JvmOverloads
    fun path(
      path: String,
      url: String,
      username: String? = null,
      password: String? = null,
      labels: Map<String, String> = emptyMap(),
    ): Builder =
      apply {
        if (path.isBlank()) invalid("A path is required")
        val credentials = userInfo(username, password)
        val target =
          if (credentials.isEmpty()) {
            url
          } else {
            // Read with the parser the agent reads a URL's credentials with
            if (runCatching { Url(url).user }.getOrNull() != null)
              invalid("The URL for path $path already has credentials; give them in it or as a username and password")
            val scheme = url.indexOf("://")
            if (scheme <= 0) invalid("The URL for path $path has no scheme")
            url.substring(0, scheme + 3) + credentials + url.substring(scheme + 3)
          }
        paths +=
          buildMap {
            put("name", path.removePrefix("/"))
            put("path", path.removePrefix("/"))
            put("url", target)
            if (labels.isNotEmpty()) put("labels", JsonObject(labels.mapValues { JsonPrimitive(it.value) }).toString())
          }
      }

    /**
     * Sets any other agent setting by its key, relative to `agent`, such as `scrapeTimeoutSecs` or
     * `http.clientTimeoutSecs` (see the agent's configuration reference). It takes precedence over a builder method
     * that sets the same key. Keys aren't checked, so a misspelled one has no effect: prefer a builder method where
     * there is one.
     */
    fun setting(
      key: String,
      value: Any,
    ): Builder = apply { settings[key] = value }

    /** The settings, ready to [start][EmbeddedAgentConfig.start]. */
    fun build(): EmbeddedAgentConfig {
      val entries = values + (if (paths.isEmpty()) emptyMap() else mapOf("pathConfigs" to paths)) + settings
      // Each key is a path under agent, such as tls.trustCertCollectionPem, which withValue reads as one.
      val config =
        entries.entries.fold(ConfigValueFactory.fromMap(mapOf("agent" to emptyMap<String, Any>())).toConfig()) {
            tree,
            (key, value),
          ->
          tree.withValue("agent.$key", ConfigValueFactory.fromAnyRef(value))
        }
      return EmbeddedAgentConfig(config)
    }

    // A username and password as a URL's user info, "user:password@", each percent-encoded as the agent decodes it;
    // empty when both are empty or absent, as unset env vars give.
    private fun userInfo(
      username: String?,
      password: String?,
    ): String =
      if (username.isNullOrEmpty() && password.isNullOrEmpty())
        ""
      else
        "${username.orEmpty().encodeURLParameter()}:${password.orEmpty().encodeURLParameter()}@"
  }

  /** Where an [EmbeddedAgentConfig] starts. */
  companion object {
    /** A builder for an [EmbeddedAgentConfig], for Java; from Kotlin, [embeddedAgent] reads better. */
    @JvmStatic
    fun builder(): Builder = Builder()

    private fun invalid(message: String): Nothing = throw ConfigLoadException(message)

    // A setting as toString shows it: a secret or a PEM only as set or not, and a URL with its credentials masked.
    private fun shown(
      value: Any?,
      key: String = "",
    ): String {
      val secret = key == "agentToken" || key.endsWith("Pem") || key.contains("password", ignoreCase = true)
      return when {
        value is Map<*, *> -> {
          value.entries
            .sortedBy { it.key.toString() }
            .joinToString(prefix = "{", postfix = "}") { (k, v) -> "$k=${shown(v, k.toString())}" }
        }

        value is List<*> -> {
          value.joinToString(prefix = "[", postfix = "]") { shown(it, key) }
        }

        secret -> {
          if (value.toString().isBlank()) "(none)" else "(set)"
        }

        key == "url" -> {
          sanitizeUrl(value.toString())
        }

        else -> {
          value.toString()
        }
      }
    }
  }
}

/**
 * Builds an [EmbeddedAgentConfig] in a block whose receiver is its [builder][EmbeddedAgentConfig.Builder]:
 *
 * ```kotlin
 * val agent = embeddedAgent { name("my-app"); proxy("proxy.example.com:50051"); scrapeSelf("my_app_metrics", 8080) }
 * agent.start()
 * ```
 */
fun embeddedAgent(block: EmbeddedAgentConfig.Builder.() -> Unit): EmbeddedAgentConfig =
  EmbeddedAgentConfig.builder().apply(block).build()
