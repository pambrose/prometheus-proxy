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

import com.pambrose.common.utils.TlsContext
import com.pambrose.common.utils.TlsUtils
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.grpc.netty.GrpcSslContexts
import io.netty.handler.ssl.ClientAuth
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Base64

/**
 * One piece of TLS material, a certificate chain, a private key, or a trust collection, given either as a file
 * ([path]) or as the PEM text itself ([pem]), never both. [name] is the settings' stem: `certChain` names the
 * `certChainFilePath` and `certChainPem` settings.
 */
internal class TlsSource(
  val name: String,
  path: String,
  val pem: String,
) {
  // Trimmed, as common-utils' file-based builders trim a path before they open it.
  val path: String = path.trim()

  val pathSetting: String get() = "${name}FilePath"
  val pemSetting: String get() = "${name}Pem"

  /** Both settings, for a message about the material however it is given. */
  val settings: String get() = "$pathSetting or $pemSetting"

  val isGiven: Boolean get() = path.isNotEmpty() || pem.isNotEmpty()

  /** Requires the material to come one way: a file and a PEM together would leave unclear which one is used. */
  fun requireOneSource() {
    require(path.isEmpty() || pem.isEmpty()) { "Set $pathSetting or $pemSetting, not both" }
  }

  // The material's bytes, from the PEM when given, else from the file, which has to exist; null when neither is.
  fun <T> withStream(block: (InputStream) -> T): T? =
    when {
      pem.isNotEmpty() -> {
        logger.info { "Reading $pemSetting" }
        ByteArrayInputStream(pem.toByteArray()).use(block)
      }

      path.isNotEmpty() -> {
        val file = File(path)
        require(file.exists() && file.isFile) { "File \"$path\" does not exist" }
        logger.info { "Reading $pathSetting: \"$path\"" }
        file.inputStream().use(block)
      }

      else -> {
        null
      }
    }

  private companion object {
    private val logger = logger {}
  }
}

/**
 * Turns the TLS settings of a Proxy or Agent into the gRPC [TlsContext] it uses, and reads TLS material given as PEM
 * text, which a platform that passes secrets as environment variables needs in place of files.
 */
internal object TlsMaterial {
  private const val PEM_BEGIN = "-----BEGIN "

  /**
   * [value], the setting named [setting], as PEM text: the value itself when it is PEM, or the base64 of it decoded.
   * A blank value is empty. A PEM written on one line with literal `\n` escapes, as some platforms' variable fields
   * keep it, gets its line breaks back. Anything else is refused, without the value in the message, since it may be a
   * private key.
   */
  fun pemText(
    setting: String,
    value: String,
  ): String {
    val trimmed = value.trim()
    return when {
      trimmed.isEmpty() -> {
        ""
      }

      trimmed.startsWith(PEM_BEGIN) -> {
        if ('\n' in trimmed) trimmed else trimmed.replace("\\n", "\n")
      }

      else -> {
        val decoded = runCatching { Base64.getMimeDecoder().decode(trimmed).decodeToString().trim() }.getOrNull()
        require(decoded != null && decoded.startsWith(PEM_BEGIN)) {
          "$setting is neither PEM text nor the base64 of PEM text"
        }
        decoded
      }
    }
  }

  /**
   * The client side's context: [trust] pins the servers to trust, or the JVM's trust store is used without one, and
   * [certChain] with [privateKey] enables mutual auth. With no PEM among them, common-utils builds it from the files,
   * as it always has; otherwise it is built here, with the same [GrpcSslContexts] calls.
   */
  fun clientContext(
    certChain: TlsSource,
    privateKey: TlsSource,
    trust: TlsSource,
  ): TlsContext =
    if (noPem(certChain, privateKey, trust)) {
      TlsUtils.buildClientTlsContext(certChain.path, privateKey.path, trust.path)
    } else {
      val builder = GrpcSslContexts.forClient()
      trust.withStream { builder.trustManager(it) }
        ?: logger.info { "No ${trust.settings} given; using the JVM default trust store" }
      val mutualAuth = certChain.isGiven
      if (mutualAuth)
        certChain.withStream { certIn -> privateKey.withStream { keyIn -> builder.keyManager(certIn, keyIn) } }
      TlsContext(builder.build(), mutualAuth)
    }

  /**
   * The server side's context, from [certChain] and [privateKey], which are required; [trust] enables mutual auth,
   * requiring a client certificate it trusts. With no PEM among them, common-utils builds it from the files, as it
   * always has; otherwise it is built here, with the same [GrpcSslContexts] calls.
   */
  fun serverContext(
    certChain: TlsSource,
    privateKey: TlsSource,
    trust: TlsSource,
  ): TlsContext =
    if (noPem(certChain, privateKey, trust)) {
      TlsUtils.buildServerTlsContext(certChain.path, privateKey.path, trust.path)
    } else {
      require(certChain.isGiven) { "Server ${certChain.settings} is required for TLS" }
      require(privateKey.isGiven) { "Server ${privateKey.settings} is required for TLS" }
      val builder =
        checkNotNull(
          certChain.withStream { certIn ->
            privateKey.withStream { keyIn -> GrpcSslContexts.forServer(certIn, keyIn) }
          },
        )
      val mutualAuth = trust.isGiven
      if (mutualAuth) {
        trust.withStream { builder.trustManager(it) }
        builder.clientAuth(ClientAuth.REQUIRE)
      }
      TlsContext(builder.build(), mutualAuth)
    }

  private fun noPem(vararg sources: TlsSource) = sources.all { it.pem.isEmpty() }

  private val logger = logger {}
}
