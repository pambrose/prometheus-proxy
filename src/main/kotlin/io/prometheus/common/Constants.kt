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

import com.google.protobuf.Empty
import io.grpc.Metadata
import io.grpc.Metadata.ASCII_STRING_MARSHALLER
import kotlin.time.Duration.Companion.seconds

internal object Messages {
  // A path or agentId of only whitespace is as unusable as an empty one: the proxy's scrape route answers a blank
  // path with a 404, so the registration guards reject both.
  const val BLANK_AGENT_ID_MSG = "Blank agentId"
  const val BLANK_PATH_MSG = "Blank path"
}

internal object GrpcConstants {
  const val AGENT_ID = "agent-id"
  val META_AGENT_ID_KEY: Metadata.Key<String> = Metadata.Key.of(AGENT_ID, ASCII_STRING_MARSHALLER)

  const val AGENT_TOKEN = "agent-token"
  val META_AGENT_TOKEN_KEY: Metadata.Key<String> = Metadata.Key.of(AGENT_TOKEN, ASCII_STRING_MARSHALLER)

  // How long a stream between an agent and the proxy goes without a message before its sender sends a keepalive. Its
  // streams carry nothing between scrapes, an agent's connection ends when any of them closes, and a reverse proxy in
  // front of the proxy closes a quiet stream: nginx after its grpc_read_timeout or client_body_timeout (60s by
  // default), Envoy after its stream_idle_timeout (5m). Each side sends them only once the other says, at
  // registration, that it takes them (stream_keepalives), since one predating them would take a keepalive for a scrape
  // request or a result.
  val STREAM_KEEPALIVE = 30.seconds
}

internal object DefaultObjects {
  val EMPTY_INSTANCE: Empty = Empty.getDefaultInstance()
}
