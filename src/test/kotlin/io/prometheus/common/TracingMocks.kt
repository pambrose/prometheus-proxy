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

import brave.Tracing
import brave.handler.MutableSpan
import brave.handler.SpanHandler
import brave.propagation.TraceContext

/**
 * A span handler that adds each span a Tracing finishes to [spans], for a spec to read what was traced: a copy, as the
 * span was when it finished. A Zipkin reporter converts a span as it finishes, so what is written to it afterwards is
 * lost, but Brave still writes it to the span's own MutableSpan, which a spec reading that would see.
 */
internal fun collectInto(spans: MutableCollection<MutableSpan>): SpanHandler =
  object : SpanHandler() {
    override fun end(
      context: TraceContext,
      span: MutableSpan,
      cause: Cause,
    ): Boolean = spans.add(MutableSpan(span))
  }

/** A real [ScrapeTracing] at [sampleRate] whose finished spans land in [spans]; close it when done. */
internal fun collectingScrapeTracing(
  spans: MutableCollection<MutableSpan>,
  sampleRate: Double = 1.0,
): ScrapeTracing = ScrapeTracing(Tracing.newBuilder().addSpanHandler(collectInto(spans)).build(), sampleRate)
