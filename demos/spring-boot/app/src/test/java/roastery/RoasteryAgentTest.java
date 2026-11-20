package roastery;

import static org.assertj.core.api.Assertions.assertThat;

import io.prometheus.Proxy;
import io.prometheus.proxy.ProxyOptions;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The roastery's agent, run: a proxy in this JVM, the roastery started by Spring Boot with its agent pointed at it,
 * and a scrape through the proxy that comes back with Actuator's metrics. It runs the agent's gRPC, Netty, and
 * serialization at the versions Spring Boot's dependency management picks for them, which compiling the roastery
 * never loads, so a Spring Boot release that breaks the agent at runtime fails here, in make spring-boot-demo-check.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // The banner and the startup lines of the roastery, the proxy, and the agent only clutter the build's output
    properties = {"logging.level.root=WARN", "spring.main.banner-mode=off"})
class RoasteryAgentTest {
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static Proxy proxy;
  private static int scrapePort;
  private static int agentPort;

  // Before Spring Boot starts the roastery: a plaintext proxy, and the agent's settings pointing at it
  @DynamicPropertySource
  static void agentSettings(DynamicPropertyRegistry registry) throws IOException {
    scrapePort = freePort();
    agentPort = freePort();
    proxy =
        new Proxy(
            new ProxyOptions(List.of("--port", "" + scrapePort, "--agent_port", "" + agentPort)),
            scrapePort,
            "",
            false,
            null);
    proxy.startAsync().awaitRunning();
    registry.add("roastery.monitoring.proxy-endpoint", () -> "localhost:" + agentPort);
    registry.add("roastery.monitoring.metrics-user", () -> "agent");
    // A password a URL's user info has to encode, as scrapeSelf does
    registry.add("roastery.monitoring.metrics-password", () -> "p@ss w:rd");
  }

  @AfterAll
  static void stopProxy() {
    proxy.stopAsync().awaitTerminated();
  }

  @Value("${local.server.port}")
  private int appPort;

  @Test
  void theProxyServesTheRoasterysMetrics() throws Exception {
    HttpRequest scrape = HttpRequest.newBuilder(URI.create("http://localhost:" + scrapePort + "/roastery_metrics")).build();
    Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    String body = "";
    while (Instant.now().isBefore(deadline)) {
      HttpResponse<String> response = HTTP.send(scrape, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        body = response.body();
        break;
      }
      Thread.sleep(250);
    }
    assertThat(body).contains("roastery_batches_roasted_total", "jvm_threads_live_threads");

    // The agent connected, so Actuator's health reports it UP, with the proxy it reached
    HttpRequest health = HttpRequest.newBuilder(URI.create("http://localhost:" + appPort + "/actuator/health")).build();
    assertThat(HTTP.send(health, HttpResponse.BodyHandlers.ofString()).body())
        .contains(
            "\"prometheusAgent\":{\"details\":{\"agent\":\"roastery-1.0\",\"proxy\":\"localhost:" + agentPort
                + "\"},\"status\":\"UP\"}");
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
