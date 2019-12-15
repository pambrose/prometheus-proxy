package io.prometheus.common

import com.github.pambrose.common.util.EMPTY_BYTE_ARRAY
import io.ktor.http.HttpStatusCode

data class ScrapeResults(val agentId: String,
                         val scrapeId: Long,
                         var validResponse: Boolean = false,
                         var statusCode: Int = HttpStatusCode.NotFound.value,
                         var contentType: String = "",
                         var contentZipped: ByteArray = EMPTY_BYTE_ARRAY,
                         var failureReason: String = "",
                         var url: String = "") {

  fun setDebugInfo(url: String, failureReason: String = "") {
    this.url = url
    this.failureReason = failureReason
  }
}