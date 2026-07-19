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

package io.prometheus.proxy

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.grpc.Context
import io.prometheus.Proxy
import java.security.MessageDigest

/**
 * A single authenticated agent identity: a token plus the set of paths it may register.
 *
 * Not a `data class`: the [tokenDigest] is a [ByteArray], whose value-based `equals`/`hashCode`
 * a `data class` would get wrong. Identities are compared by token digest via [MessageDigest.isEqual],
 * never by object equality.
 *
 * @param name identity name, used only for logging and failure messages
 * @param tokenDigest SHA-256 digest of the identity's pre-shared token
 * @param pathMatchers compiled glob patterns; an empty list authorizes every path
 * @see AgentAuthManager
 */
internal class AgentIdentity(
  val name: String,
  val tokenDigest: ByteArray,
  private val pathMatchers: List<Regex>,
) {
  /** Returns true when this identity may register [path]. An identity with no patterns allows all paths. */
  fun isAuthorized(path: String): Boolean =
    pathMatchers.isEmpty() || pathMatchers.any { it.matches(path.removePrefix("/")) }
}

/**
 * Resolves agent tokens to [AgentIdentity] instances and drives per-agent path authorization.
 *
 * Built from the proxy's `proxy.auth` identity list plus the legacy scalar `proxy.agentToken`
 * (honored as an allow-all identity for migration). When it holds at least one identity,
 * [ProxyGrpcService] installs [AgentAuthServerInterceptor]; otherwise the agent port stays open,
 * preserving the historical behavior when no token is configured.
 *
 * @see AgentAuthServerInterceptor
 * @see io.prometheus.proxy.ProxyServiceImpl.registerPath
 */
internal class AgentAuthManager private constructor(
  private val identities: List<AgentIdentity>,
) {
  /** True when at least one identity is configured; drives interceptor installation. */
  val isEnabled: Boolean get() = identities.isNotEmpty()

  /**
   * Returns the identity whose token digest matches [presentedToken], or null if none does.
   *
   * The presented token is hashed to a fixed-length digest once and compared with
   * [MessageDigest.isEqual], so neither a token's length nor its content leaks via timing.
   */
  fun resolveToken(presentedToken: String): AgentIdentity? {
    val presentedDigest = sha256(presentedToken)
    return identities.firstOrNull { MessageDigest.isEqual(presentedDigest, it.tokenDigest) }
  }

  /** A raw `proxy.auth` entry, decoupled from the generated `ConfigVals` type so [create] is unit-testable. */
  internal data class AuthEntry(
    val name: String,
    val token: String,
    val paths: List<String>,
  )

  companion object {
    private val logger = logger {}

    /** Synthetic identity name for the legacy shared `proxy.agentToken` when it is honored as allow-all. */
    internal const val LEGACY_IDENTITY_NAME = "legacy-agent-token"

    /** gRPC context key carrying the resolved identity from the interceptor to the RPC handler. */
    internal val AGENT_IDENTITY_KEY: Context.Key<AgentIdentity> = Context.key("agent-identity")

    /** Production factory: reads the identity list and legacy token off the [proxy]'s config/options. */
    operator fun invoke(proxy: Proxy): AgentAuthManager =
      create(
        authEntries = proxy.proxyConfigVals.auth.map { AuthEntry(it.name, it.token, it.paths) },
        legacyToken = proxy.options.agentToken,
      )

    /**
     * Builds the manager from raw entries, validating up front (fail-fast at startup).
     *
     * Rejects empty names/tokens and duplicate identity names. A non-empty [legacyToken] is appended
     * as an allow-all identity named [LEGACY_IDENTITY_NAME].
     */
    internal fun create(
      authEntries: List<AuthEntry>,
      legacyToken: String,
    ): AgentAuthManager {
      val configured =
        authEntries.map { entry ->
          require(entry.name.isNotEmpty()) { "proxy.auth entry has an empty name" }
          require(entry.token.isNotEmpty()) { "proxy.auth identity '${entry.name}' has an empty token" }
          AgentIdentity(entry.name, sha256(entry.token), entry.paths.map { globToRegex(it) })
        }

      val legacyIdentity =
        if (legacyToken.isEmpty()) {
          null
        } else {
          if (configured.isNotEmpty())
            logger.warn {
              "Legacy proxy.agentToken is active as an allow-all identity alongside " +
                "${configured.size} per-agent ${if (configured.size == 1) "identity" else "identities"}"
            }
          AgentIdentity(LEGACY_IDENTITY_NAME, sha256(legacyToken), emptyList())
        }

      val allIdentities = configured + listOfNotNull(legacyIdentity)

      val duplicateNames = allIdentities.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
      require(duplicateNames.isEmpty()) { "Duplicate proxy.auth identity names: ${duplicateNames.joinToString()}" }

      if (allIdentities.isNotEmpty())
        logger.info { "Per-agent auth enabled with identities: ${allIdentities.joinToString { it.name }}" }

      return AgentAuthManager(allIdentities)
    }

    /**
     * Compiles a single-segment glob into an anchored, case-sensitive [Regex]: `*` matches any run of
     * characters and `?` matches one. All other characters match literally. Paths are single-segment
     * because [ProxyPathManager] rejects multi-segment registrations.
     */
    internal fun globToRegex(glob: String): Regex {
      val pattern =
        buildString {
          append('^')
          for (ch in glob) {
            when {
              ch == '*' -> append(".*")
              ch == '?' -> append('.')
              ch.isLetterOrDigit() || ch == '_' -> append(ch)
              else -> append('\\').append(ch)
            }
          }
          append('$')
        }
      return Regex(pattern)
    }

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
  }
}
