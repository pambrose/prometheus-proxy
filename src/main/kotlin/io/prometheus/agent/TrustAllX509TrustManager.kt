/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

// https://stackoverflow.com/questions/66490928/how-can-i-disable-ktor-client-ssl-verification

object TrustAllX509TrustManager : X509TrustManager {
  private val EMPTY_CERTIFICATES: Array<X509Certificate?> = arrayOfNulls(0)

  override fun getAcceptedIssuers(): Array<X509Certificate?> = EMPTY_CERTIFICATES

  override fun checkClientTrusted(
    certs: Array<X509Certificate?>?,
    authType: String?,
  ) {
  }

  override fun checkServerTrusted(
    certs: Array<X509Certificate?>?,
    authType: String?,
  ) {
  }
}
