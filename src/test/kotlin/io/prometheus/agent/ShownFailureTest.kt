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

import io.grpc.Status
import io.grpc.StatusException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * [ShownFailure], a failure as the agent's dashboard shows it: an exception as its class and the first line of its
 * message, cut, redacted, and escaped, and a reason in the proxy's words with every line kept, escaped, and not cut
 * at an exception's limit.
 */
class ShownFailureTest : StringSpec() {
  init {
    // nginx answering a gRPC stream with a 504 gives a status whose description carries its whole HTML error page,
    // which filled the Activity view's row.
    "an exception should show only the first line of its message" {
      val nginx504 =
        StatusException(
          Status.UNAVAILABLE.withDescription(
            "HTTP status code 504\n" +
              "invalid content-type: text/html\n" +
              "headers: Metadata(:status=504,server=nginx/1.31.8)\n" +
              "DATA-----------------------------\n" +
              "<html>\r\n<head><title>504 Gateway Time-out</title></head>\r\n</html>",
          ),
        )

      ShownFailure.of(nginx504).message shouldBe "StatusException: UNAVAILABLE: HTTP status code 504"
    }

    "an exception's first line should be cut at the limit and marked with an ellipsis" {
      val line = "x".repeat(ShownFailure.SHOWN_EXCEPTION_CHARS * 2)

      ShownFailure.of(IllegalStateException("$line\nmore")).message shouldBe
        "IllegalStateException: $line".take(ShownFailure.SHOWN_EXCEPTION_CHARS) + "..."
    }

    "an exception whose first line fits should be shown whole" {
      ShownFailure.of(IllegalStateException("line 3: bad entry")).message shouldBe
        "IllegalStateException: line 3: bad entry"
    }

    "an exception whose message starts with blank lines should show its first line with text" {
      ShownFailure.of(IllegalStateException("\n  \r\nbad entry\nmore")).message shouldBe
        "IllegalStateException: bad entry"
    }

    // As before: the class, and the message as a string template shows a null one.
    "an exception without a message should show its class" {
      ShownFailure.of(RuntimeException()).message shouldBe "RuntimeException: null"
      ShownFailure.of(RuntimeException("")).message shouldBe "RuntimeException: "
    }

    "an exception's first line should still have any URL's credentials redacted and its control characters escaped" {
      ShownFailure.of(IllegalStateException("via http://ops:s3cret@lb/route\tnow\nmore")).message.apply {
        this shouldContain "IllegalStateException: via http://"
        this shouldContain "\\t"
        this shouldNotContain "s3cret"
        this shouldNotContain "more"
      }
    }

    // A reason in the proxy's words isn't an exception's message: it keeps every line, escaped, and isn't cut at an
    // exception's limit.
    "a reason should not be cut at an exception's limit, and its line breaks should be escaped" {
      val reason = "a".repeat(300) + "\nb"

      ShownFailure.of(reason).message shouldBe "a".repeat(300) + "\\nb"
    }
  }
}
