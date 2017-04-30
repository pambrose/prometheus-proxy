package io.prometheus;

import io.prometheus.client.CollectorRegistry;
import okhttp3.Request;
import okhttp3.Response;
import org.assertj.core.util.Lists;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static io.prometheus.TestConstants.OK_HTTP_CLIENT;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class AdminEmptyPathTest {

  private static final Logger logger = LoggerFactory.getLogger(AdminEmptyPathTest.class);

  private static Proxy PROXY = null;
  private static Agent AGENT = null;

  @BeforeClass
  public static void setUp()
      throws IOException, InterruptedException, TimeoutException {
    CollectorRegistry.defaultRegistry.clear();
    final List<String> args = Lists.newArrayList();
    args.add("-Dproxy.admin.port=8098");
    args.add("-Dproxy.admin.pingPath=\"\"");
    args.add("-Dproxy.admin.versionPath=\"\"");
    args.add("-Dproxy.admin.healthCheckPath=\"\"");
    args.add("-Dproxy.admin.threadDumpPath=\"\"");
    PROXY = TestUtils.startProxy(null, true, false, args);
    AGENT = TestUtils.startAgent(null, true, false, Collections.emptyList());

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
    assertThat(PROXY.getConfigVals().admin.port).isEqualTo(8098);
    assertThat(PROXY.getConfigVals().admin.pingPath).isEqualTo("");
    String url = format("http://localhost:%d/%s", PROXY.getConfigVals().admin.port, PROXY.getConfigVals().admin.pingPath);
    Request.Builder request = new Request.Builder().url(url);
    try (Response response = OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.code()).isEqualTo(404);
    }
  }

  @Test
  public void proxyVersionPathTest()
      throws Exception {
    assertThat(PROXY.getConfigVals().admin.port).isEqualTo(8098);
    assertThat(PROXY.getConfigVals().admin.versionPath).isEqualTo("");
    String url = format("http://localhost:%d/%s", PROXY.getConfigVals().admin.port, PROXY.getConfigVals().admin.versionPath);
    Request.Builder request = new Request.Builder().url(url);
    try (Response response = OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.code()).isEqualTo(404);
    }
  }

  @Test
  public void proxyHealthCheckPathTest()
      throws Exception {
    assertThat(PROXY.getConfigVals().admin.healthCheckPath).isEqualTo("");
    String url = format("http://localhost:%d/%s", PROXY.getConfigVals().admin.port, PROXY.getConfigVals().admin.healthCheckPath);
    Request.Builder request = new Request.Builder().url(url);
    try (Response response = OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.code()).isEqualTo(404);
    }
  }

  @Test
  public void proxyThreadDumpPathTest()
      throws Exception {
    assertThat(PROXY.getConfigVals().admin.threadDumpPath).isEqualTo("");
    String url = format("http://localhost:%d/%s", PROXY.getConfigVals().admin.port, PROXY.getConfigVals().admin.threadDumpPath);
    Request.Builder request = new Request.Builder().url(url);
    try (Response response = OK_HTTP_CLIENT.newCall(request.build()).execute()) {
      assertThat(response.code()).isEqualTo(404);
    }
  }
}
