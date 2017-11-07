/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus.proxy;


import brave.Span;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import io.prometheus.Proxy;
import io.prometheus.client.Summary;
import io.prometheus.grpc.ScrapeRequest;
import io.prometheus.grpc.ScrapeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Strings.isNullOrEmpty;

public class ScrapeRequestWrapper {

  private static final Logger     logger              = LoggerFactory.getLogger(ScrapeRequestWrapper.class);
  private static final AtomicLong SCRAPE_ID_GENERATOR = new AtomicLong(0);

  private final long                            createTime        = System.currentTimeMillis();
  private final CountDownLatch                  complete          = new CountDownLatch(1);
  private final AtomicReference<ScrapeResponse> scrapeResponseRef = new AtomicReference<>();

  private final AgentContext  agentContext;
  private final Span          rootSpan;
  private final Summary.Timer requestTimer;
  private final ScrapeRequest scrapeRequest;

  public ScrapeRequestWrapper(final Proxy proxy,
                              final AgentContext agentContext,
                              final Span rootSpan,
                              final String path,
                              final String accept) {
    this.agentContext = Preconditions.checkNotNull(agentContext);
    this.rootSpan = rootSpan;
    this.requestTimer = proxy.isMetricsEnabled() ? proxy.getMetrics().scrapeRequestLatency.startTimer() : null;
    ScrapeRequest.Builder builder = ScrapeRequest.newBuilder()
                                                 .setAgentId(agentContext.getAgentId())
                                                 .setScrapeId(SCRAPE_ID_GENERATOR.getAndIncrement())
                                                 .setPath(path);
    if (!isNullOrEmpty(accept))
      builder = builder.setAccept(accept);
    this.scrapeRequest = builder.build();
  }

  public ScrapeRequestWrapper annotateSpan(final String value) {
    if (this.rootSpan != null)
      this.rootSpan.annotate(value);
    return this;
  }

  public AgentContext getAgentContext() { return this.agentContext; }

  public long getScrapeId() { return this.scrapeRequest.getScrapeId(); }

  public ScrapeRequest getScrapeRequest() { return this.scrapeRequest; }

  public ScrapeResponse getScrapeResponse() { return this.scrapeResponseRef.get(); }

  public ScrapeRequestWrapper setScrapeResponse(final ScrapeResponse scrapeResponse) {
    this.scrapeResponseRef.set(scrapeResponse);
    return this;
  }

  public long ageInSecs() { return (System.currentTimeMillis() - this.createTime) / 1000;}

  public ScrapeRequestWrapper markComplete() {
    if (this.requestTimer != null)
      this.requestTimer.observeDuration();
    this.complete.countDown();
    return this;
  }

  public boolean waitUntilCompleteMillis(final long waitMillis) {
    try {
      return this.complete.await(waitMillis, TimeUnit.MILLISECONDS);
    }
    catch (InterruptedException e) {
      // Ignore
    }
    return false;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("scrapeId", scrapeRequest.getScrapeId())
                      .add("path", scrapeRequest.getPath())
                      .toString();
  }
}
