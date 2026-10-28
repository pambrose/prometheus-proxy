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

import java.net.URI

/**
 * Where Prometheus scrapes the paths that service discovery advertises, parsed from `sdTargetPrefix`.
 *
 * Prometheus takes each HTTP SD target as a scrape's `__address__`, which must be `host[:port]`: an address with a
 * `/` is refused, so a URL there -- the form the docs gave -- left Prometheus with no targets at all. A URL is
 * accepted still, and split into what Prometheus does take.
 *
 * @property address the target, `host[:port]`
 * @property scheme the `__scheme__` label, or null to leave the scheme to the scrape config
 * @property pathPrefix prepended to each path's `__metrics_path__`: empty, or a path without a trailing `/`
 */
internal data class SdTarget(
  val address: String,
  val scheme: String?,
  val pathPrefix: String,
) {
  companion object {
    /**
     * Parses [value]: `host[:port]`, or an `http` or `https` URL whose host and port are the target.
     *
     * @throws IllegalArgumentException for anything else, naming `sdTargetPrefix`
     */
    fun parse(value: String): SdTarget {
      require(value.isNotBlank()) { "sdTargetPrefix is empty" }
      val prefix = value.trim()
      return if ("://" in prefix) parseUrl(prefix) else parseAddress(prefix)
    }

    private fun parseUrl(prefix: String): SdTarget {
      val uri = toUri(prefix)
      val scheme = uri.scheme.lowercase()
      require(scheme == "http" || scheme == "https") { "sdTargetPrefix '$prefix' must use http or https" }
      val host = uri.host
      require(!host.isNullOrEmpty()) { "sdTargetPrefix '$prefix' has no host" }
      require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "sdTargetPrefix '$prefix' can't have user info, a query, or a fragment"
      }
      val address = if (uri.port == -1) host else "$host:${uri.port}"
      return SdTarget(address, scheme, uri.rawPath.orEmpty().trimEnd('/'))
    }

    private fun parseAddress(prefix: String): SdTarget {
      val uri = toUri("http://$prefix")
      require(!uri.host.isNullOrEmpty() && uri.rawPath.isNullOrEmpty() && uri.rawUserInfo == null) {
        "sdTargetPrefix '$prefix' must be host:port, or an http or https URL"
      }
      return SdTarget(prefix, null, "")
    }

    private fun toUri(value: String): URI =
      runCatching { URI(value) }.getOrElse { e ->
        throw IllegalArgumentException("sdTargetPrefix '$value' must be host:port, or an http or https URL", e)
      }
  }
}
