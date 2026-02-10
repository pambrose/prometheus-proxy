/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

internal object Messages {
  const val EMPTY_AGENT_ID_MSG = "Empty agentId"
  const val EMPTY_PATH_MSG = "Empty path"
}

internal object GrpcConstants {
  const val AGENT_ID = "agent-id"
  val META_AGENT_ID_KEY: Metadata.Key<String> = Metadata.Key.of(AGENT_ID, ASCII_STRING_MARSHALLER)
}

internal object DefaultObjects {
  val EMPTY_INSTANCE: Empty = Empty.getDefaultInstance()
}
