/*
 * Copyright © 2025 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.harness.support

import com.github.pambrose.common.util.getBanner
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.agent.AgentOptions
import io.prometheus.common.Utils.getVersionDesc
import io.prometheus.harness.support.HarnessConstants.PROXY_PORT
import io.prometheus.proxy.ProxyOptions
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.nio.channels.ClosedSelectorException

@Serializable(with = CustomEnumSerializer::class)
enum class MyEnum(
  val type: String,
) {
  A("a"),
  B("b"),
}

object CustomEnumSerializer : KSerializer<MyEnum> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MyEnum", PrimitiveKind.STRING)

  override fun serialize(
    encoder: Encoder,
    value: MyEnum,
  ) {
    encoder.encodeString(value.type)
  }

  override fun deserialize(decoder: Decoder): MyEnum {
    val value = decoder.decodeString()
    return MyEnum.entries.find { it.type == value }
      ?: throw IllegalArgumentException("Unknown enum value: $value")
  }
}

object TestUtils {
  private val logger = KotlinLogging.logger {}

  @JvmStatic
  fun main(args: Array<String>) {
    println(Json.encodeToString(MyEnum.A))
  }

  fun startProxy(
    serverName: String = "",
    adminEnabled: Boolean = false,
    debugEnabled: Boolean = false,
    metricsEnabled: Boolean = false,
    args: List<String> = emptyList(),
  ): Proxy {
    logger.apply {
      info { getBanner("banners/proxy.txt", logger) }
      info { getVersionDesc(false) }
    }

    val proxyOptions = ProxyOptions(
      mutableListOf<String>()
        .apply {
          addAll(HarnessConstants.CONFIG_ARG)
          addAll(args)
          add("-Dproxy.admin.enabled=$adminEnabled")
          add("-Dproxy.admin.debugEnabled=$debugEnabled")
          add("-Dproxy.metrics.enabled=$metricsEnabled")
        },
    )
    return Proxy(
      options = proxyOptions,
      proxyHttpPort = PROXY_PORT,
      inProcessServerName = serverName,
      testMode = true,
    ) { startSync() }
  }

  fun startAgent(
    serverName: String = "",
    adminEnabled: Boolean = false,
    debugEnabled: Boolean = false,
    metricsEnabled: Boolean = false,
    scrapeTimeoutSecs: Int = -1,
    chunkContentSizeKbs: Int = -1,
    maxConcurrentClients: Int = -1,
    args: List<String> = emptyList(),
  ): Agent {
    logger.apply {
      info { getBanner("banners/agent.txt", logger) }
      info { getVersionDesc(false) }
    }

    val agentOptions = AgentOptions(
      args = mutableListOf<String>()
        .apply {
          addAll(HarnessConstants.CONFIG_ARG)
          addAll(args)
          add("-Dagent.admin.enabled=$adminEnabled")
          add("-Dagent.admin.debugEnabled=$debugEnabled")
          add("-Dagent.metrics.enabled=$metricsEnabled")
          if (scrapeTimeoutSecs != -1)
            add("-Dagent.scrapeTimeoutSecs=$scrapeTimeoutSecs")
          if (chunkContentSizeKbs != -1)
            add("-Dagent.chunkContentSizeKbs=$chunkContentSizeKbs")
          if (maxConcurrentClients != -1)
            add("-Dagent.http.maxConcurrentClients=$maxConcurrentClients")
        },
      exitOnMissingConfig = false,
    )
    return Agent(options = agentOptions, inProcessServerName = serverName, testMode = true) { startSync() }
  }
}

fun exceptionHandler(logger: KLogger) =
  CoroutineExceptionHandler { _, e ->
    if (e is ClosedSelectorException)
      logger.info { "CoroutineExceptionHandler caught: $e" }
    else
      logger.warn(e) { "CoroutineExceptionHandler caught: $e" }
  }

fun String.withPrefix(prefix: String = "http://localhost:") = if (this.startsWith(prefix)) this else (prefix + this)
