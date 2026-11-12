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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.kotest.core.spec.style.StringSpec
import io.prometheus.common.Lincheck
import io.prometheus.grpc.chunkedScrapeResponse
import io.prometheus.grpc.headerData
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.Validate

// Lincheck races a chunked header opening a transfer for a scrape against the scrape's HTTP handler returning. The
// handler stops tracking the scrape and then removes its transfer; the header looks the scrape up, stores the transfer,
// and then checks that the scrape is still tracked. Neither takes a lock, so the two orders are what keep a transfer
// from outliving its scrape, which an agent holding its stream open could otherwise do for up to
// maxZippedContentSizeMBytes a scrape (November #13). Checked after every run: once the handler has returned, no
// transfer is left for its scrape.
class ChunkedTransferLifetimeLincheckTest : StringSpec() {
  init {
    tags(Lincheck)

    // Every operation runs in the parallel part. A finish() in the sequential part before it would stop tracking the
    // scrape before any header could race it, and one in the part after would remove a transfer left behind before
    // validate() could see it.
    "a chunked transfer never outlives its scrape, under model checking" {
      ModelCheckingOptions()
        // Lincheck 3.7 throws an NPE in SnapshotTracker while minimizing a failure found from a Kotest coroutine,
        // hiding the failure itself; report the unminimized scenario instead.
        .minimizeFailedScenario(false)
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(THREADS)
        .actorsPerThread(ACTORS_PER_THREAD)
        .actorsBefore(0)
        .actorsAfter(0)
        .check(ChunkedTransferLifetimeOps::class)
    }

    "a chunked transfer never outlives its scrape, under stress" {
      StressOptions()
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(THREADS)
        .actorsPerThread(ACTORS_PER_THREAD)
        .actorsBefore(0)
        .actorsAfter(0)
        .check(ChunkedTransferLifetimeOps::class)
    }
  }

  companion object {
    private const val ITERATIONS = 30
    private const val INVOCATIONS = 300
    private const val THREADS = 3
    private const val ACTORS_PER_THREAD = 2
  }
}

class ChunkedTransferLifetimeOps {
  private val scrapes = ScrapeRequestManager()
  private val transfers = AgentContextManager(isTestMode = true)
  private val owner = AgentContext("owner")

  // Tracked before any operation runs, as the handler tracks it before sending it to the agent.
  private val scrape =
    ScrapeRequestWrapper(owner, "metrics", "", "", null, false)
      .also { scrapes.tryAddToScrapeRequestMap(it, MAX_IN_FLIGHT) }

  private val header =
    chunkedScrapeResponse {
      header = headerData {
        headerValidResponse = true
        headerScrapeId = scrape.scrapeId
        headerAgentId = owner.agentId
        headerStatusCode = 200
        headerContentType = "text/plain"
      }
    }

  // ProxyHttpRoutes.dispatchScrapeRequest's finally: the request, and then its transfer.
  @Operation
  fun finish() {
    scrapes.removeFromScrapeRequestMap(scrape.scrapeId)
    transfers.removeChunkedContext(scrape.scrapeId)
  }

  // ProxyServiceImpl's readHeader once the owner check has passed: one lookup, the store, and the check after it. A
  // copy, since readHeader is a private member of a service that needs a Proxy, which this spec can't build without
  // MockK; ProxyServiceImplTest checks the product's, with the handler's removal injected between the lookup and the
  // store, and ProxyHttpRoutesTest checks that the handler removes the transfer.
  @Operation
  fun openTransfer() {
    val wrapper = scrapes.getScrapeRequest(scrape.scrapeId) ?: return
    val context = ChunkedContext(header, MAX_ZIPPED_BYTES, wrapper.agentContext)
    if (transfers.putChunkedContext(scrape.scrapeId, context) && !scrapes.containsScrapeRequest(scrape.scrapeId))
      transfers.removeChunkedContext(scrape.scrapeId, context)
  }

  // ProxyServiceImpl's readChunk on a transfer whose scrape is no longer tracked: it drops the transfer it found.
  @Operation
  fun readChunk() {
    val context = transfers.getChunkedContext(scrape.scrapeId) ?: return
    if (!scrapes.containsScrapeRequest(scrape.scrapeId))
      transfers.removeChunkedContext(scrape.scrapeId, context)
  }

  @Validate
  fun validate() {
    check(scrapes.containsScrapeRequest(scrape.scrapeId) || transfers.getChunkedContext(scrape.scrapeId) == null) {
      "Scrape ${scrape.scrapeId}'s handler returned, but its chunked transfer was left open"
    }
  }

  companion object {
    private const val MAX_IN_FLIGHT = 2
    private const val MAX_ZIPPED_BYTES = 1024L
  }
}
