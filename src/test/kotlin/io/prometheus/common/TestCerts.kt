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

import java.io.File
import java.util.Base64

/**
 * The repository's test certificates (testing/certs): a CA, a server certificate it signed for `*.test.google.fr`, and
 * a client certificate for mutual TLS, with their text and base64, for the specs that give TLS material as PEM.
 */
object TestCerts {
  const val CA_CERT = "testing/certs/ca.pem"
  const val SERVER_CERT = "testing/certs/server1.pem"
  const val SERVER_KEY = "testing/certs/server1.key"
  const val CLIENT_CERT = "testing/certs/client.pem"
  const val CLIENT_KEY = "testing/certs/client.key"

  /** A name the server certificate was issued for, which an agent dialing localhost checks it against. */
  const val SERVER_AUTHORITY = "foo.test.google.fr"

  /** The text of the file at [path]. */
  fun read(path: String): String = File(path).readText()

  /** The file at [path] as base64 on one line, the form a one-line variable field holds a PEM in. */
  fun base64(path: String): String = Base64.getEncoder().encodeToString(File(path).readBytes())
}
