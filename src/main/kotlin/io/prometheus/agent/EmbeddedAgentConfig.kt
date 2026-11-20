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
import io.prometheus.Agent
import io.prometheus.common.Utils.sanitizeUrl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLEncoder

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
 * use throws [io.prometheus.common.ConfigLoadException]. [toConfig] gives the tree itself.
 */
class EmbeddedAgentConfig private constructor(
  private val config: Config,
  private val summary: String,
) {
  /** The settings as the `Config` tree a config file would hold, rooted at `agent`. */
  fun toConfig(): Config = config

  /**
   * Starts an agent with these settings, as [Agent.startAsyncAgent][io.prometheus.Agent.Companion.startAsyncAgent]
   * does with a `Config`, and returns its handle once it has started.
   *
   * @param logBanner When `true` (default), logs the agent's ASCII banner and version on startup.
   * @throws io.prometheus.common.ConfigLoadException when a setting is invalid.
   * @throws IllegalStateException if the agent fails to start.
   */
  @JvmOverloads
  fun start(logBanner: Boolean = true): EmbeddedAgentInfo = Agent.startAsyncAgent(config, logBanner)

  /** The settings, with the token, the private key, and the credentials in a path's URL left out. */
  override fun toString(): String = summary

  /**
   * Builds an [EmbeddedAgentConfig]. Each method returns the builder, so calls chain in Java and read as statements in
   * Kotlin's [embeddedAgent] block; a method called twice keeps its last value, and [path] and [scrapeSelf] add a path
   * each time. Anything the builder has no method for, [setting] sets by its key.
   */
  class Builder internal constructor() {
    private var name: String? = null
    private var endpoints: List<String> = emptyList()
    private var token: String? = null
    private var trustPem: String? = null
    private var authority: String? = null
    private var clientCertPem: String? = null
    private var clientKeyPem: String? = null
    private var consolidated: Boolean? = null
    private var metricsPort: Int? = null
    private val paths = mutableListOf<PathEntry>()
    private val settings = linkedMapOf<String, Any>()

    private class PathEntry(
      val path: String,
      val url: String,
      val labels: Map<String, String>,
    )

    /** The agent's name, as the proxy's dashboard and logs show it (`agent.name`). */
    fun name(name: String): Builder = apply { this.name = name }

    /**
     * The proxy the agent connects to, as `host` or `host:port` (the port defaults to 50051), or several, in the
     * order the agent fails over through them (`agent.proxy.endpoints`).
     */
    fun proxy(vararg endpoints: String): Builder = apply { this.endpoints = endpoints.toList() }

    /** The token the agent presents to the proxy, which has to match the proxy's (`agent.agentToken`). */
    fun token(token: String): Builder = apply { this.token = token }

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
        trustPem = pem
        this.authority = authority
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
        clientCertPem = certChainPem
        clientKeyPem = privateKeyPem
      }

    /** Whether the agent registers its paths in consolidated mode, alongside other agents (`agent.consolidated`). */
    @JvmOverloads
    fun consolidated(consolidated: Boolean = true): Builder = apply { this.consolidated = consolidated }

    /**
     * Turns on the agent's own metrics endpoint, on [port], which is off unless asked for (`agent.metrics.enabled`
     * and `agent.metrics.port`).
     */
    fun agentMetrics(port: Int): Builder =
      apply {
        requirePort(port)
        metricsPort = port
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
    ): Builder =
      apply {
        requirePort(port)
        addPath(path, "http://${userInfo(username, password)}127.0.0.1:$port/${metricsPath.removePrefix("/")}", labels)
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
        val credentials = userInfo(username, password)
        val target =
          if (credentials.isEmpty()) {
            url
          } else {
            require(runCatching { URI(url).rawUserInfo }.getOrNull() == null) {
              "The URL for path $path already has credentials; give them in the URL or as a username and password"
            }
            val scheme = url.indexOf("://")
            require(scheme > 0) { "The URL for path $path has no scheme" }
            url.substring(0, scheme + 3) + credentials + url.substring(scheme + 3)
          }
        addPath(path, target, labels)
      }

    /**
     * Sets any other agent setting by its key, relative to `agent`, such as `scrapeTimeoutSecs` or
     * `http.clientTimeoutSecs` (see the agent's configuration reference). It takes precedence over a builder method
     * that sets the same key. A key the agent doesn't know is ignored, so prefer a builder method where there is one.
     */
    fun setting(
      key: String,
      value: Any,
    ): Builder = apply { settings[key] = value }

    /** The settings, ready to [start][EmbeddedAgentConfig.start]. */
    fun build(): EmbeddedAgentConfig {
      val agent = linkedMapOf<String, Any>()
      name?.let { agent["name"] = it }
      if (endpoints.isNotEmpty()) agent["proxy"] = mapOf("endpoints" to endpoints)
      token?.let { agent["agentToken"] = it }
      val tls =
        listOfNotNull(
          trustPem?.let { "trustCertCollectionPem" to it },
          authority?.let { "overrideAuthority" to it },
          clientCertPem?.let { "certChainPem" to it },
          clientKeyPem?.let { "privateKeyPem" to it },
        ).toMap()
      if (tls.isNotEmpty()) agent["tls"] = tls
      consolidated?.let { agent["consolidated"] = it }
      metricsPort?.let { agent["metrics"] = mapOf("enabled" to true, "port" to it) }
      if (paths.isNotEmpty())
        agent["pathConfigs"] = paths.map { entry ->
          buildMap {
            put("name", entry.path)
            put("path", entry.path)
            put("url", entry.url)
            if (entry.labels.isNotEmpty())
              put("labels", JsonObject(entry.labels.mapValues { JsonPrimitive(it.value) }).toString())
          }
        }

      // A setting's key is a path, such as http.clientTimeoutSecs, which withValue reads as one.
      val config =
        settings.entries.fold(ConfigValueFactory.fromMap(mapOf("agent" to agent)).toConfig()) { tree, (key, value) ->
          tree.withValue("agent.$key", ConfigValueFactory.fromAnyRef(value))
        }
      return EmbeddedAgentConfig(config, summary())
    }

    // What toString shows: everything but the token, the private key, and a path's credentials.
    private fun summary(): String {
      val tls =
        when {
          !clientCertPem.isNullOrEmpty() -> "mutual"
          !trustPem.isNullOrEmpty() -> "on"
          else -> "off"
        }
      val shown =
        listOfNotNull(
          name?.let { "name=$it" },
          "proxy=${endpoints.ifEmpty { listOf("(default)") }}",
          "token=${if (token.isNullOrEmpty()) "none" else "set"}",
          "tls=$tls",
          authority?.let { "authority=$it" },
          consolidated?.let { "consolidated=$it" },
          metricsPort?.let { "agentMetrics=$it" },
          "paths=${paths.map { "/${it.path} -> ${sanitizeUrl(it.url)}" }}",
          settings.keys.takeIf { it.isNotEmpty() }?.let { "settings=$it" },
        )
      return "EmbeddedAgentConfig(${shown.joinToString()})"
    }

    private fun addPath(
      path: String,
      url: String,
      labels: Map<String, String>,
    ) {
      require(path.isNotBlank()) { "A path is required" }
      paths += PathEntry(path.removePrefix("/"), url, labels)
    }

    private fun requirePort(port: Int) = require(port in 1..65535) { "A port must be from 1 to 65535: $port" }

    // A username and password as a URL's user info, "user:password@", each percent-encoded; empty when both are
    // empty or absent, as an unset env var gives. URLEncoder writes a space as '+', which user info keeps as a plus,
    // so it becomes %20.
    private fun userInfo(
      username: String?,
      password: String?,
    ): String {
      if (username.isNullOrEmpty() && password.isNullOrEmpty()) return ""
      return "${encoded(username)}:${encoded(password)}@"
    }

    private fun encoded(text: String?) = URLEncoder.encode(text.orEmpty(), Charsets.UTF_8).replace("+", "%20")
  }

  /** Where an [EmbeddedAgentConfig] starts. */
  companion object {
    /** A builder for an [EmbeddedAgentConfig], for Java; from Kotlin, [embeddedAgent] reads better. */
    @JvmStatic
    fun builder(): Builder = Builder()
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
