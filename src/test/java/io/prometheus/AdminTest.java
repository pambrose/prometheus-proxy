package io.prometheus;

import io.prometheus.client.CollectorRegistry;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class AdminTest {

  private static final Logger logger = LoggerFactory.getLogger(AdminTest.class);

  private static Proxy PROXY = null;
  private static Agent AGENT = null;

  @BeforeClass
  public static void setUp()
      throws IOException, InterruptedException, TimeoutException {
    CollectorRegistry.defaultRegistry.clear();
    PROXY = TestUtils.startProxy(null, true, false);
    AGENT = TestUtils.startAgent(null, true, false);

    AGENT.awaitInitialConnection(5, SECONDS);
  }

  @AfterClass
  public static void takeDown()
      throws InterruptedException, TimeoutException {
    PROXY.stopAsync();
    PROXY.awaitTerminated(5, SECONDS);
    AGENT.stopAsync();
    AGENT.awaitTerminated(5, SECONDS);
  }

  @Test
  public void proxyPingPathTest()
      throws Exception {
    String url = format("http://localhost:%d/%s", PROXY.getConfigVals().admin.port, PROXY.getConfigVals().admin.pingPath);
    Request.Builder request = new Request.Builder().url(url);
    try (Response response = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.code()).isEqualTo(200);
      assertThat(response.body().string()).startsWith("pong");
    }
  }

  @Test
  public void agentPingPathTest()
      throws Exception {
    String url = format("http://localhost:%d/%s", AGENT.getConfigVals().admin.port, AGENT.getConfigVals().admin.pingPath);
    Request.Builder request = new Request.Builder().url(url);
    try (Response response = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.code()).isEqualTo(200);
      assertThat(response.body().string()).startsWith("pong");
    }
  }

  @Test
  public void proxyHealthCheckPathTest()
      throws Exception {
    String url = format("http://localhost:%d/%s", PROXY.getConfigVals().admin.port, PROXY.getConfigVals().admin.healthCheckPath);
    Request.Builder request = new Request.Builder().url(url);
    try (Response response = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.code()).isEqualTo(200);
      assertThat(response.body().string().length()).isGreaterThan(10);
    }
  }

  @Test
  public void agentHealthCheckPathTest()
      throws Exception {
    String url = format("http://localhost:%d/%s", AGENT.getConfigVals().admin.port, AGENT.getConfigVals().admin.healthCheckPath);
    Request.Builder request = new Request.Builder().url(url);
    try (Response response = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.body().string().length()).isGreaterThan(10);
    }
  }

  @Test
  public void proxyThreadDumpPathTest()
      throws Exception {
    String url = format("http://localhost:%d/%s", PROXY.getConfigVals().admin.port, PROXY.getConfigVals().admin.theadtDumpPath);
    Request.Builder request = new Request.Builder().url(url);
    try (Response response = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.body().string().length()).isGreaterThan(10);
    }
  }

  @Test
  public void agentThreadDumpPathTest()
      throws Exception {
    String url = format("http://localhost:%d/%s", AGENT.getConfigVals().admin.port, AGENT.getConfigVals().admin.theadtDumpPath);
    Request.Builder request = new Request.Builder().url(url);
    try (Response response = TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.body().string().length()).isGreaterThan(10);
    }
  }

}
