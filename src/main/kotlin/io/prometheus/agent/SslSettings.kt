/*
 * Copyright © 2023 Paul Ambrose (pambrose@mac.com)
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

import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// https://github.com/Hakky54/mutual-tls-ssl/blob/master/client/src/main/java/nl/altindag/client/service/KtorCIOHttpClientService.kt

@Suppress("unused")
object SslSettings {
  fun getKeyStore(
    fileName: String,
    password: String,
  ): KeyStore =
    KeyStore.getInstance(KeyStore.getDefaultType())
      .apply {
        val keyStoreFile = FileInputStream(fileName)
        val keyStorePassword = password.toCharArray()
        load(keyStoreFile, keyStorePassword)
      }

  fun getTrustManagerFactory(
    fileName: String,
    password: String,
  ): TrustManagerFactory? =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      .apply {
        init(getKeyStore(fileName, password))
      }

  fun getSslContext(
    fileName: String,
    password: String,
  ): SSLContext? =
    SSLContext.getInstance("TLS")
      .apply {
        init(null, getTrustManagerFactory(fileName, password)?.trustManagers, null)
      }

  fun getTrustManager(
    fileName: String,
    password: String,
  ): X509TrustManager =
    getTrustManagerFactory(fileName, password)?.trustManagers?.first { it is X509TrustManager } as X509TrustManager
}
