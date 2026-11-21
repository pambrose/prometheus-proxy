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

package io.prometheus.agent

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.prometheus.common.ScrapeResults
import kotlin.time.Duration.Companion.milliseconds

/**
 * [AgentScrapeHistory], each path's last scrapes as the agent saw them, and [AgentScrapeRecord.of], which records one
 * from the results the agent built.
 */
class AgentScrapeHistoryTest : StringSpec() {
  private fun record(statusCode: Int = 200) =
    AgentScrapeRecord(
      statusCode = statusCode,
      outcome = if (statusCode == 200) "success" else "unsuccessful",
      durationMillis = 12,
      bytes = 100,
      filteredBytes = 100,
      gzipped = false,
      chunked = false,
      failure = "",
      fromProxyDashboard = false,
    )

  private fun results(
    statusCode: Int = 200,
    zipped: ByteArray = ByteArray(0),
    rawSize: Int = 0,
    filteredSize: Int = 0,
    failureReason: String = "",
    counterMsg: String = "success",
  ) = ScrapeResults(
    srAgentId = "agent-1",
    srScrapeId = 1,
    srValidResponse = statusCode == 200,
    srStatusCode = statusCode,
    srZipped = zipped.isNotEmpty(),
    srContentAsZipped = zipped,
    srFailureReason = failureReason,
    scrapeCounterMsg = counterMsg,
    rawSize = rawSize,
    filteredSize = filteredSize,
  )

  init {
    "each path should keep its last scrapes, newest first, at most the history's size" {
      val history = AgentScrapeHistory(historySize = 3, pathCount = { 2 })
      listOf(200, 500, 200, 503).forEach { history.record("orders_metrics", record(it)) }
      history.record("payments_metrics", record(200))

      history.snapshot().apply {
        getValue("orders_metrics").map { it.statusCode } shouldContainExactly [503, 200, 500]
        getValue("payments_metrics").map { it.statusCode } shouldContainExactly [200]
      }
    }

    // As the path manager keys a path, so a scrape of /orders_metrics and one of orders_metrics share a ring, the one
    // the dashboard joins to the path.
    "a path should be keyed without its leading slashes" {
      val history = AgentScrapeHistory(historySize = 5, pathCount = { 1 })
      history.record("/orders_metrics", record())
      history.record("orders_metrics", record())

      history.snapshot().keys shouldContainExactly ["orders_metrics"]
      history.snapshot().getValue("orders_metrics").size shouldBe 2
    }

    // A reconnect clears the registered paths, so a path's ring can't be forgotten as it unregisters; a discovery file
    // that churns path names would grow the history without end. The bound evicts the path scraped least recently.
    "past the paths the agent knows and the gone ones it keeps, the path scraped least recently should go" {
      val history = AgentScrapeHistory(historySize = 5, pathCount = { 1 }, maxGonePaths = 1)
      history.record("a", record())
      history.record("b", record())
      history.record("a", record())
      history.record("c", record())

      history.snapshot().keys shouldContainExactly ["a", "c"]
    }

    "a history should start empty" {
      AgentScrapeHistory(historySize = 5, pathCount = { 0 }).snapshot().shouldBeEmpty()
    }

    "a record should hold the body's size before and after the filter, and whether it was gzipped and chunked" {
      AgentScrapeRecord.of(
        results(zipped = ByteArray(2048), rawSize = 90_000, filteredSize = 30_000),
        duration = 42.milliseconds,
        chunkContentSizeBytes = 1024,
      ).apply {
        statusCode shouldBe 200
        outcome shouldBe "success"
        durationMillis shouldBe 42
        bytes shouldBe 90_000
        filteredBytes shouldBe 30_000
        gzipped.shouldBeTrue()
        chunked.shouldBeTrue()
        isSuccess.shouldBeTrue()
        fromProxyDashboard.shouldBeFalse()
      }
    }

    // As AgentGrpcService.forwardScrapeResult decides: a gzipped body under the chunk size goes in one message.
    "a gzipped body under the chunk size should not be chunked, and one not gzipped never is" {
      AgentScrapeRecord.of(results(zipped = ByteArray(1023)), 1.milliseconds, chunkContentSizeBytes = 1024).apply {
        gzipped.shouldBeTrue()
        chunked.shouldBeFalse()
      }
      AgentScrapeRecord.of(results(rawSize = 10), 1.milliseconds, chunkContentSizeBytes = 1).apply {
        gzipped.shouldBeFalse()
        chunked.shouldBeFalse()
      }
    }

    // The reason comes from an HTTP client's exception, which a target can shape; it is shown on the dashboard.
    "a failed scrape should keep its reason, cut and with its control characters escaped" {
      AgentScrapeRecord.of(
        results(503, failureReason = "Connection refused\u001b[2J" + "x".repeat(1000), counterMsg = "failure"),
        1.milliseconds,
        chunkContentSizeBytes = 1024,
      ).apply {
        statusCode shouldBe 503
        outcome shouldBe "failure"
        isSuccess.shouldBeFalse()
        failure shouldNotContain "\u001b"
        (failure.length < 600).shouldBeTrue()
      }
    }

    "a scrape the proxy's dashboard asked for should be marked as one" {
      AgentScrapeRecord.of(results().asDashboardScrape(), 1.milliseconds, chunkContentSizeBytes = 1024)
        .fromProxyDashboard
        .shouldBeTrue()
    }
  }
}
