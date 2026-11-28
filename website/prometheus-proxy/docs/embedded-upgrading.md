---
icon: lucide/circle-arrow-up
---

# Upgrading an Embedded Agent

What each release since 4.0 changed for code that [embeds the agent](embedded-agent.md), and what to do about it.
A release that changed nothing an embedding app sees isn't listed; the
[changelog](https://github.com/pambrose/prometheus-proxy/blob/master/CHANGELOG.md) lists every change.

## Upgrading from 4.7.0

- The agent's [dashboard](agent-dashboard.md), when it's on, allows framing by default, so any page can show it in a
  frame, such as a portal's. 4.7.0 refused framing on every dashboard response. To keep refusing it, call
  `setting("dashboard.framingEnabled", false)`, or set the Ktor plugin's or the Spring Boot starter's
  `settings.dashboard.framingEnabled` to `false` (see [Exposure](web-dashboard.md#exposure)).
- `shutdown()` and `close()`, and `Agent.stop()`, throw an `IllegalStateException`, with the
  `java.util.concurrent.TimeoutException` as its cause, for an agent without a `stopTimeout` that doesn't stop within
  30 seconds. Through 4.7.0 they threw the `TimeoutException` itself, which they didn't declare, so Java code couldn't
  catch it, and a catch of `RuntimeException` missed it. Kotlin code that caught `TimeoutException` there should catch
  `IllegalStateException` (see [`EmbeddedAgentInfo`](embedded-api.md#embeddedagentinfo)).
- The Spring Boot starter's stop waits for the agent at most its new `prometheus-proxy.agent.stop-timeout`, 30 seconds
  by default, then returns, with a warning, and the agent goes on stopping on a daemon thread of its own. Through 4.7.0
  it waited the agent's own 30 seconds, then threw out of `stop()`. Set `stop-timeout` inside the window your platform
  gives the app to stop (see [Properties](spring-boot-starter.md#properties)).
- `startIfConfigured(onFailure)` logs an exception its `onFailure` throws, and still returns `null`, as it does for the
  handlers the builder's new `onFailure` adds. Through 4.7.0 the exception escaped the call. An app that can't run
  without its agent should call `start()`, which throws, instead.
- An agent refuses a path of more than one segment, such as `app/metrics` or `//metrics`, which no scrape could reach:
  through 4.7.0 the agent started with it and served nothing there. A `pathConfigs` entry in the config
  `startAsyncAgent()` is given now fails its start with `ConfigLoadException`, naming the path. The builder's `path()`,
  `scrapeSelf()`, `source()`, and `agentMetricsAt()` throw it as they're called, before `startIfConfigured()` can catch
  it. A Ktor plugin or Spring Boot starter path, its `path`, `metrics-proxy-path`, or a `paths` entry's, keeps the agent
  off, telling the `onFailure` handlers, or fails a `required` agent's app. Give each path one segment, such as
  `app_metrics`.
- The builder's `path()` throws `ConfigLoadException` for a blank URL, and `path()` and `scrapeSelf()` for `/` alone as
  a path, as they're called. Through 4.7.0 they took them, and the agent skipped the target at startup with only a
  warning. A Ktor plugin or Spring Boot starter `paths` entry without a `url`, as an unset environment variable leaves
  it, or with `/` alone as its `path`, therefore keeps the agent off, telling the `onFailure` handlers, or fails a
  `required` agent's app, where the agent started without that target. Give the entry its URL, or leave it out. A file
  `path` or `labels` the Ktor plugin can't read, such as `labels = "team=web"` where a section belongs, fails the
  agent's start in the same way, where it failed `install(PrometheusProxyAgent)`, the agent enabled or not.
- An agent refuses to start with a proxy endpoint it can't dial: one with no host, such as `:50051` or `[]`, or with
  anything but `:port` after a bracketed IPv6 address, such as `[::1]50051`, whether it comes from `proxy()`,
  `fromEnvironment()`'s `PROMETHEUS_PROXY`, a `setting()`, or `PROXY_HOSTNAME`. `start()` throws
  `ConfigLoadException`, naming it, and `startIfConfigured()` logs that and returns `null`. Through 4.7.0, as the only
  or first endpoint, one with no host failed the start with a less clear message; as a later failover entry, it let the
  agent start and failed each connect that reached it, and `[::1]50051` dialed `[::1]` on the default port. Write the
  endpoint in full, such as `[::1]:50051`.
- A TLS certificate, key, or trust collection file that exists but can't be read, such as one the app's user may not
  open, and TLS material the agent can't build its TLS context from, fail the start with `ConfigLoadException`, naming
  the setting, as any setting the agent rejects does. Through 4.7.0 they reached the app as a `FileNotFoundException`
  or an `SSLException`. Code that caught either there should catch `ConfigLoadException`.

## Upgrading from 4.6.x

- The agent no longer brings Logback. Through 4.6.0, `ch.qos.logback:logback-classic` came with it, at runtime
  scope, and displaced an application's own SLF4J provider: Spring Boot failed to start an app on Log4j2, or ignored
  its Log4j2 configuration. The agent's loggers now go to your application's provider. An application that logged
  through the Logback the agent brought, with no SLF4J provider of its own, now gets SLF4J's warning that it found
  none, and no logs, so add Logback to it (see [Logging](embedded-debugging.md#logging)).
- The agent reads `DASHBOARD_ENABLED`, `DASHBOARD_PORT`, `DASHBOARD_PATH`, `DASHBOARD_HOST`, `DASHBOARD_USERNAME`,
  `DASHBOARD_PASSWORD`, and `METRICS_PROXY_PATH` from your application's environment, which through 4.6.0 only the
  proxy read, or nothing did. If your application sets any of them for its own use, the agent now opens its dashboard,
  on port 8095 unless `DASHBOARD_PORT` names another, or serves its metrics at that path, and a value it can't parse,
  such as an empty `DASHBOARD_PORT`, or a dashboard port that's taken, fails its start.
- An agent started with `startAsyncAgent()` or `EmbeddedAgentConfig` registers no JVM shutdown hook. Through 4.6.0 it
  did, and on SIGTERM it stopped at the same time as your application's own hooks, in no set order. Stop it with
  `shutdown()` or `close()` where your application stops its own services (see
  [`EmbeddedAgentInfo`](embedded-api.md#embeddedagentinfo)); one that's never stopped ends with the JVM, without draining the scrapes
  in flight.
- An agent started with `startAsyncAgent()` or `EmbeddedAgentConfig` runs on a daemon thread, so it no longer keeps the
  JVM running. Through 4.6.0 its thread did: an application that failed after starting the agent never exited, and a
  program whose `main()` only started the agent kept running. That program now exits at once, unless the agent's admin
  or metrics port is on, whose servers' threads keep the JVM running until the agent stops. Keep `main()` running for
  as long as the agent is needed, or start the agent as the standalone agent does, with `startSyncAgent()`.
- In an application on Spring Boot 4.0 or 4.1, set `kotlin-coroutines.version` to `1.11.0` and
  `prometheus-client.version` to `1.10.0`: Spring Boot's dependency management sets kotlinx-coroutines and the
  Prometheus Java client below the versions the agent runs on. The agent now checks both as it starts, and refuses to
  start with either too old, naming the property to set, where it failed once it ran. Spring Boot 4.2 needs neither
  (see [Spring Boot Starter](spring-boot-starter.md)).
- A JVM `Error`, such as an `OutOfMemoryError`, in the agent's connection to the proxy stops the agent, as one in a
  scrape already did, and the app runs on without it, with `isConnected` false. Through 4.6.0 it ended only the
  connection, and the agent reconnected and ran on in whatever state the `Error` left it in.

## Upgrading from 4.4.x

- `startAsyncAgent()` throws `ConfigLoadException` for a config that loads but holds a setting the agent can't use,
  as listed under [`Agent.startAsyncAgent()`](embedded-api.md#agentstartasyncagent). Through 4.4.2 those escaped as an
  `IllegalArgumentException` or a Typesafe `ConfigException`, and a `ConfigException`'s message began with the config
  URL, credentials included. Code that caught either should catch `ConfigLoadException`; the
  `IllegalArgumentException` is now its cause.
- The library jar no longer carries a `logback.xml`. Through 4.4.2 it did, and Logback, and Spring Boot ahead of
  your `logback-spring.xml`, took it for your application's configuration. An application with no Logback
  configuration of its own now gets Logback's default, which logs everything at DEBUG, so add one (see
  [Logging](embedded-debugging.md#logging)); one whose own configuration was being replaced gets it back.
- Maven builds now get Jackson 2.23.0 and netty-tcnative 2.0.86.Final, natives included, through the agent, where
  they got Jackson 2.12.7 and the 2.0.84.Final natives. A Jackson your build declares itself still wins.
- The agent's generated gRPC classes need protobuf-java 4.36.2 or later, which Maven and Gradle builds now resolve
  through it, where they got 3.25.9. Your own code generated by protoc 3.x keeps working on it. A build that pins
  protobuf-java to 3.x must move the pin to 4.36.2 or later, or the agent fails when it connects, with a
  `NoClassDefFoundError` for `com.google.protobuf.RuntimeVersion`.

## Upgrading from 4.1.x

- The agent moved from the Prometheus Java client 0.x (`io.prometheus:simpleclient` 0.16.0) to 1.x
  (`io.prometheus:prometheus-metrics-core` 1.9.0), and its metrics now register in the 1.x default registry. A
  host that serves the 0.x `CollectorRegistry` no longer exposes them. To serve both from one endpoint, serve the
  1.x registry and bridge your 0.x metrics into it: add `io.prometheus:prometheus-metrics-simpleclient-bridge`,
  at the same version as the `prometheus-metrics-core` the agent brings, and call
  `SimpleclientCollector.builder().register()` once at startup.
- `simpleclient` no longer arrives through the agent. If your application relied on getting it that way,
  declare it yourself.
- With the agent's metrics endpoint on, the `_created` series of its counters and histograms are gone (set
  `IO_PROMETHEUS_EXPORTER_INCLUDE_CREATED_TIMESTAMPS=true` to bring them back), and with the JVM exports on, the
  JVM memory metrics are renamed (see [JVM Metrics](monitoring.md#jvm-metrics)).

## Upgrading from 4.0.x

- `grpc-netty-shaded` no longer arrives through the agent, which uses the unshaded `grpc-netty` transport.
  If your application relied on getting it that way, declare it yourself.
- `startAsyncAgent()` now waits for the agent to start and throws if it fails, where it used to return a
  handle to an agent that had failed (see [`Agent.startAsyncAgent()`](embedded-api.md#agentstartasyncagent)).
- When a config URL's error message has to be redacted, the `ConfigLoadException`'s cause is a stand-in
  exception with the redacted message and the original stack trace, not Typesafe's `ConfigException`, so
  code that checks `cause is ConfigException` no longer matches it.
