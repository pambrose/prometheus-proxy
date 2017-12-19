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

package io.prometheus;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.prometheus.agent.RequestFailureException;
import io.prometheus.common.Utils;
import okhttp3.Request;
import okhttp3.Response;
import spark.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static io.prometheus.TestConstants.RANDOM;
import static java.lang.Math.abs;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

public class Tests {

  public static void missingPathTest()
      throws Exception {
    String url = format("http://localhost:%d/", TestConstants.PROXY_PORT);
    Request.Builder request = new Request.Builder().url(url);
    try (Response response = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.code()).isEqualTo(404);
    }
  }

  public static void invalidPathTest()
      throws Exception {
    String url = format("http://localhost:%d/invalid_path", TestConstants.PROXY_PORT);
    Request.Builder request = new Request.Builder().url(url);
    try (Response response = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.code()).isEqualTo(404);
    }
  }

  public static void addRemovePathsTest(final Agent agent)
      throws Exception {

    // Take into account pre-existing paths already registered
    int originalSize = agent.pathMapSize();

    int cnt = 0;
    for (int i = 0; i < TestConstants.REPS; i++) {
      final String path = format("test-%d", i);
      agent.registerPath(path, format("http://localhost:%d/%s", TestConstants.PROXY_PORT, path));
      cnt++;
      assertThat(agent.pathMapSize()).isEqualTo(originalSize + cnt);
      agent.unregisterPath(path);
      cnt--;
      assertThat(agent.pathMapSize()).isEqualTo(originalSize + cnt);
    }
  }

  public static void threadedAddRemovePathsTest(final Agent agent)
      throws Exception {
    final List<String> paths = Lists.newArrayList();
    final AtomicInteger cnt = new AtomicInteger(0);
    final CountDownLatch latch1 = new CountDownLatch(TestConstants.REPS);
    final CountDownLatch latch2 = new CountDownLatch(TestConstants.REPS);

    // Take into account pre-existing paths already registered
    int originalSize = agent.pathMapSize();

    IntStream.range(0, TestConstants.REPS)
             .forEach(val -> {
               TestConstants.EXECUTOR_SERVICE.submit(
                   () -> {
                     final String path = format("test-%d", cnt.getAndIncrement());
                     synchronized (paths) {
                       paths.add(path);
                     }
                     try {
                       final String url = format("http://localhost:%d/%s", TestConstants.PROXY_PORT, path);
                       agent.registerPath(path, url);
                       latch1.countDown();
                     }
                     catch (RequestFailureException e) {
                       e.printStackTrace();
                     }
                   });
             });

    assertThat(latch1.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(paths.size()).isEqualTo(TestConstants.REPS);
    assertThat(agent.pathMapSize()).isEqualTo(originalSize + TestConstants.REPS);

    paths.forEach(
        (path) -> {
          TestConstants.EXECUTOR_SERVICE.submit(
              () -> {
                try {
                  agent.unregisterPath(path);
                  latch2.countDown();
                }
                catch (RequestFailureException e) {
                  e.printStackTrace();
                }
              });
        });

    // Wait for all unregistrations to complete
    assertThat(latch2.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(agent.pathMapSize()).isEqualTo(originalSize);
  }

  public static void invalidAgentUrlTest(final Agent agent)
      throws Exception {
    final String badPath = "badPath";

    agent.registerPath(badPath, "http://localhost:33/metrics");

    String url = format("http://localhost:%d/%s", TestConstants.PROXY_PORT, badPath);
    Request.Builder request = new Request.Builder().url(url);
    try (Response response = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.code()).isEqualTo(404);
    }

    agent.unregisterPath(badPath);
  }

  public static void timeoutTest(final Agent agent)
      throws Exception {
    int agentPort = 9700;
    String proxyPath = "proxy-timeout";
    String agentPath = "agent-timeout";

    Service http = Service.ignite();
    http.port(agentPort)
        .get(format("/%s", agentPath),
             (req, res) -> {
               res.type("text/plain");
               Utils.INSTANCE.sleepForSecs(10);
               return "I timed out";
             });
    String agentUrl = format("http://localhost:%d/%s", agentPort, agentPath);
    agent.registerPath("/" + proxyPath, agentUrl);

    String proxyUrl = format("http://localhost:%d/%s", TestConstants.PROXY_PORT, proxyPath);
    Request.Builder request = new Request.Builder().url(proxyUrl);
    try (Response response = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.code()).isEqualTo(404);
    }

    agent.unregisterPath("/" + proxyPath);
    http.stop();
  }

  public static void proxyCallTest(final Agent agent,
                                   final int httpServerCount,
                                   final int pathCount,
                                   final int queryCount,
                                   final long pauseMillis)
      throws Exception {

    final int startingPort = 9600;
    final List<Service> httpServers = Lists.newArrayList();
    final Map<Integer, Integer> pathMap = Maps.newConcurrentMap();

    // Take into account pre-existing paths already registered
    int originalSize = agent.pathMapSize();

    // Create the endpoints
    IntStream.range(0, httpServerCount)
             .forEach(i -> {
               Service http = Service.ignite();
               http.port(startingPort + i)
                   .threadPool(30, 10, 1000)
                   .get(format("/agent-%d", i),
                        (req, res) -> {
                          res.type("text/plain");
                          return format("value: %d", i);
                        });
               httpServers.add(http);
             });

    // Create the paths
    for (int i = 0; i < pathCount; i++) {
      int index = abs(RANDOM.nextInt()) % httpServers.size();
      String url = format("http://localhost:%d/agent-%d", startingPort + index, index);
      agent.registerPath(format("proxy-%d", i), url);
      pathMap.put(i, index);
    }

    assertThat(agent.pathMapSize()).isEqualTo(originalSize + pathCount);

    // Call the proxy sequentially
    for (int i = 0; i < queryCount; i++) {
      callProxy(pathMap);
      Utils.INSTANCE.sleepForMillis(pauseMillis);
    }

    // Call the proxy in parallel
    int threadedQueryCount = 100;
    final CountDownLatch latch = new CountDownLatch(threadedQueryCount);
    IntStream.range(0, threadedQueryCount)
             .forEach(
                 i -> {
                   TestConstants.EXECUTOR_SERVICE.submit(
                       () -> {
                         try {
                           callProxy(pathMap);
                           latch.countDown();
                         }
                         catch (Exception e) {
                           e.printStackTrace();
                         }
                       });
                 });

    assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

    final AtomicInteger errorCnt = new AtomicInteger();
    pathMap.forEach(
        (k, v) -> {
          try {
            agent.unregisterPath(format("proxy-%d", k));
          }
          catch (RequestFailureException e) {
            errorCnt.incrementAndGet();
          }
        });

    assertThat(errorCnt.get()).isEqualTo(0);
    assertThat(agent.pathMapSize()).isEqualTo(originalSize);

    httpServers.forEach(Service::stop);
  }

  private static void callProxy(final Map<Integer, Integer> pathMap)
      throws Exception {
    // Choose one of the pathMap values
    int index = abs(RANDOM.nextInt() % pathMap.size());
    int httpVal = pathMap.get(index);
    String url = format("http://localhost:%d/proxy-%d", TestConstants.PROXY_PORT, index);
    Request.Builder request = new Request.Builder().url(url);
    try (Response response = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.code()).isEqualTo(200);
      String body = response.body().string();
      assertThat(body).isEqualTo(format("value: %d", httpVal));
    }
  }
}
