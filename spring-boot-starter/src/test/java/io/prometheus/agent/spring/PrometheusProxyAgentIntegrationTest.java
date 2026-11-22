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

package io.prometheus.agent.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.prometheus.Proxy;
import io.prometheus.proxy.ProxyOptions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// An app with the starter and Actuator, and no web server: the agent serves its registry in-process, through a proxy
// in the same JVM, at the versions of the agent's libraries Spring Boot picks.
@SpringBootTest(
    classes = PrometheusProxyAgentIntegrationTest.RoasteryApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.application.name=roastery",
      "prometheus-proxy.agent.labels.team=roasting",
      // The startup lines of the app, the proxy, and the agent only clutter the build's output
      "logging.level.root=WARN",
      "spring.main.banner-mode=off"
    })
class PrometheusProxyAgentIntegrationTest {
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  // Fixed, below the ephemeral range, where a port taken from ServerSocket(0) and bound again later can go to another
  // connection in between; the root project's TestPorts lists them (STARTER_PROXY_*), so no root spec binds them.
  private static final int SCRAPE_PORT = 11400;
  private static final int AGENT_PORT = 11401;

  private static Proxy proxy;

  @Autowired
  private HealthContributorRegistry health;

  // Before Spring Boot starts the app: a plaintext proxy with service discovery, and the agent pointed at it
  @DynamicPropertySource
  static void agentSettings(DynamicPropertyRegistry registry) {
    List<String> args = List.of("--port", "" + SCRAPE_PORT, "--agent_port", "" + AGENT_PORT, "--sd_enabled");
    proxy = new Proxy(new ProxyOptions(args), SCRAPE_PORT, "", false, null);
    proxy.startAsync().awaitRunning();
    registry.add("prometheus-proxy.agent.proxy", () -> "localhost:" + AGENT_PORT);
  }

  @AfterAll
  static void stopProxy() {
    proxy.stopAsync().awaitTerminated();
  }

  @Test
  void theProxyServesTheAppsMetricsAndHealthReportsTheAgent() throws Exception {
    String body = scrapeWhenRegistered("/roastery_metrics");

    // The app's own meter and one Spring Boot binds, which /actuator/prometheus would have served
    assertThat(body).contains("beans_roasted_total 4.0", "jvm_threads_live_threads");
    // The path's labels, in the proxy's service discovery
    assertThat(get("/discovery")).containsPattern("\"team\":\\s*\"roasting\"");

    // The agent connected, so its health component, under the name Actuator shows it by, is UP
    Health agent = ((HealthIndicator) health.getContributor("prometheusAgent")).health();
    assertThat(agent.getStatus()).isEqualTo(Status.UP);
    assertThat(agent.getDetails()).containsEntry("agent", "roastery").containsEntry("proxy", "localhost:" + AGENT_PORT);
  }

  // The proxy answers 404 for the path until the agent has connected and registered it
  private static String scrapeWhenRegistered(String path) throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    HttpResponse<String> response = send(path);
    while (response.statusCode() != 200 && Instant.now().isBefore(deadline)) {
      Thread.sleep(250);
      response = send(path);
    }
    assertThat(response.statusCode()).isEqualTo(200);
    return response.body();
  }

  private static String get(String path) throws Exception {
    return send(path).body();
  }

  private static HttpResponse<String> send(String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + SCRAPE_PORT + path)).build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class RoasteryApp {
    @Bean
    Counter beansRoasted(MeterRegistry registry) {
      Counter counter = Counter.builder("beans_roasted").description("Batches of beans roasted").register(registry);
      counter.increment(4);
      return counter;
    }
  }
}
