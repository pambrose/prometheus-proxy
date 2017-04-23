package com.sudothought;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sudothought.agent.Agent;
import com.sudothought.common.Utils;
import okhttp3.Request;
import okhttp3.Response;
import spark.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.sudothought.TestConstants.RANDOM;
import static java.lang.Math.abs;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

public class Tests {

  public static void missingPathTest()
      throws IOException {
    String url = format("http://localhost:%d/", TestConstants.PROXY_PORT);
    Request.Builder request = new Request.Builder().url(url);
    try (Response respone = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(respone.code()).isEqualTo(404);
    }
  }

  public static void invalidPathTest()
      throws IOException {
    String url = format("http://localhost:%d/invalid_path", TestConstants.PROXY_PORT);
    Request.Builder request = new Request.Builder().url(url);
    try (Response respone = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(respone.code()).isEqualTo(404);
    }
  }

  public static void addRemovePathsTest(final Agent agent)
      throws ConnectException {

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
      throws ConnectException, InterruptedException {
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
                     catch (ConnectException e) {
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
                catch (ConnectException e) {
                  e.printStackTrace();
                }
              });
        });

    // Wait for all unregistrations to complete
    assertThat(latch2.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(agent.pathMapSize()).isEqualTo(originalSize);
  }

  public static void invalidAgentUrlTest(final Agent agent)
      throws IOException {
    final String badPath = "badPath";

    agent.registerPath(badPath, "http://localhost:33/metrics");

    String url = format("http://localhost:%d/%s", TestConstants.PROXY_PORT, badPath);
    Request.Builder request = new Request.Builder().url(url);
    try (Response respone = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(respone.code()).isEqualTo(404);
    }

    agent.unregisterPath(badPath);
  }

  public static void timeoutTest(final Agent agent)
      throws IOException {
    int agentPort = 9700;
    String proxyPath = "proxy-timeout";
    String agentPath = "agent-timeout";

    Service http = Service.ignite();
    http.port(agentPort)
        .get(format("/%s", agentPath),
             (req, res) -> {
               res.type("text/plain");
               Utils.sleepForSecs(10);
               return "I timed out";
             });
    String agentUrl = format("http://localhost:%d/%s", agentPort, agentPath);
    agent.registerPath("/" + proxyPath, agentUrl);

    String proxyUrl = format("http://localhost:%d/%s", TestConstants.PROXY_PORT, proxyPath);
    Request.Builder request = new Request.Builder().url(proxyUrl);
    try (Response respone = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(respone.code()).isEqualTo(404);
    }

    agent.unregisterPath("/" + proxyPath);
    http.stop();
  }

  public static void proxyCallTest(final Agent agent,
                                   final int httpServerCount,
                                   final int pathCount,
                                   final int queryCount)
      throws IOException, InterruptedException {

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
    for (int i = 0; i < queryCount; i++)
      callProxy(pathMap);

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
                         catch (IOException e) {
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
          catch (ConnectException e) {
            errorCnt.incrementAndGet();
          }
        });

    assertThat(errorCnt.get()).isEqualTo(0);
    assertThat(agent.pathMapSize()).isEqualTo(originalSize);

    httpServers.forEach(Service::stop);
  }

  private static void callProxy(final Map<Integer, Integer> pathMap)
      throws IOException {
    // Choose one of the pathMap values
    int index = abs(RANDOM.nextInt() % pathMap.size());
    int httpVal = pathMap.get(index);
    String url = format("http://localhost:%d/proxy-%d", TestConstants.PROXY_PORT, index);
    Request.Builder request = new Request.Builder().url(url);
    try (Response respone = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      String body = respone.body().string();
      assertThat(body).isEqualTo(format("value: %d", httpVal));
    }
  }
}
