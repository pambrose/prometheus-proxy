package com.sudothought;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sudothought.agent.Agent;
import com.sudothought.agent.AgentArgs;
import com.sudothought.common.ConfigVals;
import com.sudothought.common.Utils;
import com.sudothought.proxy.Proxy;
import com.sudothought.proxy.ProxyArgs;
import com.typesafe.config.Config;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.sudothought.common.EnvVars.AGENT_CONFIG;
import static com.sudothought.common.EnvVars.PROXY_CONFIG;
import static com.sudothought.common.Utils.sleepForSecs;
import static java.lang.Math.abs;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

public class InProcessTest {

  private static final Logger          logger           = LoggerFactory.getLogger(InProcessTest.class);
  private static final ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();
  private static final OkHttpClient    OK_HTTP_CLIENT   = new OkHttpClient();

  private static final Random RANDOM     = new Random();
  private static final int    REPS       = 1000;
  private static final int    PROXY_PORT = 9500;

  private static Proxy PROXY = null;
  private static Agent AGENT = null;

  @BeforeClass
  public static void setUp()
      throws Exception {

    final String[] argv = {"--config", "https://dl.dropboxusercontent.com/u/481551/prometheus/junit-config.conf"};
    final String serverName = "server1";

    logger.info(Utils.getBanner("banners/proxy.txt"));
    final ProxyArgs proxyArgs = new ProxyArgs();
    proxyArgs.parseArgs(Proxy.class.getName(), argv);

    final Config proxyConfig = Utils.readConfig(proxyArgs.config, PROXY_CONFIG, false);
    final ConfigVals proxyConfigVals = new ConfigVals(proxyConfig);
    proxyArgs.assignArgs(proxyConfigVals);

    PROXY = new Proxy(proxyConfigVals,
                      proxyArgs.grpc_port,
                      PROXY_PORT,
                      !proxyArgs.disable_metrics,
                      proxyArgs.metrics_port,
                      serverName);
    PROXY.start();

    logger.info(Utils.getBanner("banners/agent.txt"));
    final AgentArgs agentArgs = new AgentArgs();
    agentArgs.parseArgs(Agent.class.getName(), argv);

    final Config agentConfig = Utils.readConfig(agentArgs.config, AGENT_CONFIG, true);
    final ConfigVals configVals = new ConfigVals(agentConfig);
    agentArgs.assignArgs(configVals);

    AGENT = new Agent(configVals,
                      serverName,
                      agentArgs.agent_name,
                      agentArgs.proxy_host,
                      !agentArgs.disable_metrics,
                      agentArgs.metrics_port);
    AGENT.start();

    // Wait long enough to trigger heartbeat for code coverage
    sleepForSecs(15);
  }

  @AfterClass
  public static void takeDown() {
    EXECUTOR_SERVICE.shutdownNow();
    PROXY.stop();
    AGENT.stop();
    sleepForSecs(5);
  }

  @Test
  public void addRemovePathsTest()
      throws ConnectException {
    // Take into account pre-existing paths already registered
    int originalSize = AGENT.pathMapSize();

    int cnt = 0;
    for (int i = 0; i < REPS; i++) {
      final String path = format("test-%d", i);
      AGENT.registerPath(path, format("http://localhost:%d/%s", PROXY_PORT, path));
      cnt++;
      assertThat(AGENT.pathMapSize()).isEqualTo(originalSize + cnt);
      AGENT.unregisterPath(path);
      cnt--;
      assertThat(AGENT.pathMapSize()).isEqualTo(originalSize + cnt);
    }
  }

  @Test
  public void threadedAddRemovePathsTest()
      throws ConnectException, InterruptedException {
    final List<String> paths = Lists.newArrayList();
    final AtomicInteger cnt = new AtomicInteger(0);
    final CountDownLatch latch1 = new CountDownLatch(REPS);
    final CountDownLatch latch2 = new CountDownLatch(REPS);

    // Take into account pre-existing paths already registered
    int originalSize = AGENT.pathMapSize();

    IntStream.range(0, REPS)
             .forEach(val -> {
               EXECUTOR_SERVICE.submit(
                   () -> {
                     final String path = format("test-%d", cnt.getAndIncrement());
                     synchronized (paths) {
                       paths.add(path);
                     }
                     try {
                       final String url = format("http://localhost:%d/%s", PROXY_PORT, path);
                       AGENT.registerPath(path, url);
                       latch1.countDown();
                     }
                     catch (ConnectException e) {
                       e.printStackTrace();
                     }
                   });
             });

    assertThat(latch1.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(paths.size()).isEqualTo(REPS);
    assertThat(AGENT.pathMapSize()).isEqualTo(originalSize + REPS);

    paths.forEach(
        (path) -> {
          EXECUTOR_SERVICE.submit(
              () -> {
                try {
                  AGENT.unregisterPath(path);
                  latch2.countDown();
                }
                catch (ConnectException e) {
                  e.printStackTrace();
                }
              });
        });

    // Wait for all unregistrations to complete
    assertThat(latch2.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(AGENT.pathMapSize()).isEqualTo(originalSize);
  }

  @Test
  public void missingPathTest()
      throws IOException {
    String url = format("http://localhost:%d/", PROXY_PORT);
    Request.Builder request = new Request.Builder().url(url);
    Response respone = OK_HTTP_CLIENT.newCall(request.build()).execute();
    assertThat(respone.code()).isEqualTo(404);
  }

  @Test
  public void invalidPathTest()
      throws IOException {
    String url = format("http://localhost:%d/invalid_path", PROXY_PORT);
    Request.Builder request = new Request.Builder().url(url);
    Response respone = OK_HTTP_CLIENT.newCall(request.build()).execute();
    assertThat(respone.code()).isEqualTo(404);
  }

  @Test
  public void invalidAgentUrlTest()
      throws IOException {
    final String badPath = "badPath";

    AGENT.registerPath(badPath, "http://localhost:33/metrics");

    String url = format("http://localhost:%d/%s", PROXY_PORT, badPath);
    Request.Builder request = new Request.Builder().url(url);
    Response respone = OK_HTTP_CLIENT.newCall(request.build()).execute();
    assertThat(respone.code()).isEqualTo(404);

    AGENT.unregisterPath(badPath);
  }

  @Test
  public void timeoutTest()
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
    AGENT.registerPath("/" + proxyPath, agentUrl);

    String proxyUrl = format("http://localhost:%d/%s", PROXY_PORT, proxyPath);
    Request.Builder request = new Request.Builder().url(proxyUrl);
    Response respone = OK_HTTP_CLIENT.newCall(request.build()).execute();
    assertThat(respone.code()).isEqualTo(404);

    AGENT.unregisterPath("/" + proxyPath);
  }


  @Test
  public void proxyCallTest()
      throws IOException, InterruptedException {

    final int httpServerCount = 25;
    final int pathCount = 100;
    final int queryCount = 500;

    final int startingPort = 9600;
    final List<Service> httpServers = Lists.newArrayList();
    final Map<Integer, Integer> pathMap = Maps.newConcurrentMap();

    // Take into account pre-existing paths already registered
    int originalSize = AGENT.pathMapSize();

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
      AGENT.registerPath(format("proxy-%d", i), url);
      pathMap.put(i, index);
    }

    assertThat(AGENT.pathMapSize()).isEqualTo(originalSize + pathCount);

    // Call the proxy sequentially
    for (int i = 0; i < queryCount; i++)
      callProxy(pathMap);

    // Call the proxy in parallel
    int threadedQueryCount = 100;
    final CountDownLatch latch = new CountDownLatch(threadedQueryCount);
    IntStream.range(0, threadedQueryCount)
             .forEach(
                 i -> {
                   EXECUTOR_SERVICE.submit(
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
    pathMap.forEach((k, v) -> {
      try {
        AGENT.unregisterPath(format("proxy-%d", k));
      }
      catch (ConnectException e) {
        errorCnt.incrementAndGet();
      }
    });

    assertThat(errorCnt.get()).isEqualTo(0);
    assertThat(AGENT.pathMapSize()).isEqualTo(originalSize);

    httpServers.forEach(Service::stop);
  }

  private void callProxy(final Map<Integer, Integer> pathMap)
      throws IOException {
    // Choose one of the pathMap values
    int index = abs(RANDOM.nextInt() % pathMap.size());
    int httpVal = pathMap.get(index);
    String url = format("http://localhost:%d/proxy-%d", PROXY_PORT, index);
    Request.Builder request = new Request.Builder().url(url);
    Response respone = OK_HTTP_CLIENT.newCall(request.build()).execute();
    String body = respone.body().string();
    assertThat(body).isEqualTo(format("value: %d", httpVal));
  }

}
