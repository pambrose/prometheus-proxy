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

package io.prometheus.proxy.dashboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.prometheus.proxy.dashboard.ProxyDashboardService.Companion.isOriginAllowed

/**
 * The WebSocket Origin rule. The same-origin policy does not cover WebSockets, so a page on another site can open
 * one to the dashboard from an operator's browser; the Origin header is what tells the dashboard's own page apart
 * from a foreign one. The live refusal is covered by `ProxyWebDashboardTest`.
 */
class ProxyDashboardServiceTest : StringSpec() {
  init {
    "a request with no Origin header should be allowed, since only browsers send one" {
      isOriginAllowed(origin = null, host = "proxy:8094", allowedOrigins = emptyList()).shouldBeTrue()
    }

    "an origin naming the dashboard's own host should be allowed" {
      isOriginAllowed("http://proxy:8094", "proxy:8094", emptyList()).shouldBeTrue()
      // Hosts are case-insensitive, and a default port is omitted from both headers.
      isOriginAllowed("https://Proxy.Example.com", "proxy.example.com", emptyList()).shouldBeTrue()
    }

    "a foreign origin should be refused" {
      isOriginAllowed("https://evil.example.com", "proxy:8094", emptyList()).shouldBeFalse()
      // The same host on another port is a different origin.
      isOriginAllowed("http://proxy:9999", "proxy:8094", emptyList()).shouldBeFalse()
    }

    "an origin listed in allowedOrigins should be allowed" {
      // Behind a reverse proxy the browser's origin names the public host, not the Host the proxy receives.
      isOriginAllowed("https://dash.example.com", "10.0.0.5:8094", listOf("https://dash.example.com")).shouldBeTrue()
      // Case and a trailing slash in the configured entry do not matter.
      isOriginAllowed("https://dash.example.com", "10.0.0.5:8094", listOf("HTTPS://Dash.Example.com/")).shouldBeTrue()
      isOriginAllowed("https://other.example.com", "10.0.0.5:8094", listOf("https://dash.example.com")).shouldBeFalse()
    }

    "an opaque or malformed origin should be refused" {
      // Sandboxed iframes and file:// pages send the literal "null".
      isOriginAllowed("null", "proxy:8094", emptyList()).shouldBeFalse()
      isOriginAllowed("not a url", "proxy:8094", emptyList()).shouldBeFalse()
      isOriginAllowed("http://proxy:8094", host = null, allowedOrigins = emptyList()).shouldBeFalse()
    }
  }
}
