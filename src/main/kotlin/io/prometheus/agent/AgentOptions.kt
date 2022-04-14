/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.agent

import com.beust.jcommander.Parameter
import io.prometheus.Agent
import io.prometheus.common.BaseOptions
import io.prometheus.common.EnvVars.*
import mu.KLogging
import kotlin.time.Duration.Companion.seconds

class AgentOptions(argv: Array<String>, exitOnMissingConfig: Boolean) :
  BaseOptions(Agent::class.java.name, argv, AGENT_CONFIG.name, exitOnMissingConfig) {

  constructor(args: List<String>, exitOnMissingConfig: Boolean) :
      this(args.toTypedArray(), exitOnMissingConfig)

  constructor(configFilename: String, exitOnMissingConfig: Boolean) :
      this(listOf("--config", configFilename), exitOnMissingConfig)

  @Parameter(names = ["-p", "--proxy"], description = "Proxy hostname")
  var proxyHostname = ""
    private set

  @Parameter(names = ["-n", "--name"], description = "Agent name")
  var agentName = ""
    private set

  @Parameter(names = ["-o", "--consolidated"], description = "Consolidated Agent")
  var consolidated = false
    private set

  @Parameter(names = ["--over", "--override"], description = "Override Authority")
  var overrideAuthority = ""
    private set

  @Parameter(names = ["--timeout"], description = "Scrape timeout time (seconds)")
  var scrapeTimeoutSecs = -1
    private set

  @Parameter(names = ["--max_retries"], description = "Scrape max retries")
  var scrapeMaxRetries = -1
    private set

  @Parameter(names = ["--chunk"], description = "Threshold for chunking content to Proxy and buffer size (KBs)")
  var chunkContentSizeKbs = -1
    private set

  @Parameter(names = ["--gzip"], description = "Minimum size for content to be gzipped (bytes)")
  var minGzipSizeBytes = -1
    private set

  init {
    parseOptions()
  }

  override fun assignConfigVals() {

    configVals.agent
      .also { agentConfigVals ->

        if (proxyHostname.isEmpty()) {
          val configHostname = agentConfigVals.proxy.hostname
          proxyHostname = PROXY_HOSTNAME.getEnv(
            if (":" in configHostname)
              configHostname
            else
              "$configHostname:${agentConfigVals.proxy.port}"
          )
        }
        logger.info { "proxyHostname: $proxyHostname" }

        if (agentName.isEmpty())
          agentName = AGENT_NAME.getEnv(agentConfigVals.name)
        logger.info { "agentName: $agentName" }

        if (!consolidated)
          consolidated = CONSOLIDATED.getEnv(agentConfigVals.consolidated)
        logger.info { "consolidated: $consolidated" }

        if (scrapeTimeoutSecs == -1)
          scrapeTimeoutSecs = SCRAPE_TIMEOUT_SECS.getEnv(agentConfigVals.scrapeTimeoutSecs)
        logger.info { "scrapeTimeoutSecs: ${scrapeTimeoutSecs.seconds}" }

        if (scrapeMaxRetries == -1)
          scrapeMaxRetries = SCRAPE_MAX_RETRIES.getEnv(agentConfigVals.scrapeMaxRetries)
        logger.info { "scrapeMaxRetries: $scrapeMaxRetries" }

        if (chunkContentSizeKbs == -1)
          chunkContentSizeKbs = CHUNK_CONTENT_SIZE_KBS.getEnv(agentConfigVals.chunkContentSizeKbs)
        // Multiply the value time KB
        chunkContentSizeKbs *= 1024
        logger.info { "chunkContentSizeKbs: $chunkContentSizeKbs" }

        if (minGzipSizeBytes == -1)
          minGzipSizeBytes = MIN_GZIP_SIZE_BYTES.getEnv(agentConfigVals.minGzipSizeBytes)
        logger.info { "minGzipSizeBytes: $minGzipSizeBytes" }

        if (overrideAuthority.isEmpty())
          overrideAuthority = OVERRIDE_AUTHORITY.getEnv(agentConfigVals.tls.overrideAuthority)
        logger.info { "overrideAuthority: $overrideAuthority" }

        assignAdminEnabled(agentConfigVals.admin.enabled)
        assignAdminPort(agentConfigVals.admin.port)
        assignMetricsEnabled(agentConfigVals.metrics.enabled)
        assignMetricsPort(agentConfigVals.metrics.port)
        assignDebugEnabled(agentConfigVals.admin.debugEnabled)

        assignCertChainFilePath(agentConfigVals.tls.certChainFilePath)
        assignPrivateKeyFilePath(agentConfigVals.tls.privateKeyFilePath)
        assignTrustCertCollectionFilePath(agentConfigVals.tls.trustCertCollectionFilePath)

        logger.info { "agent.internal.cioTimeoutSecs: ${agentConfigVals.internal.cioTimeoutSecs.seconds}" }
        logger.info { "agent.scrapeTimeoutSecs: ${agentConfigVals.scrapeTimeoutSecs.seconds}" }
      }
  }

  companion object : KLogging()
}