---
icon: lucide/bug
---

# Debugging an Embedded Agent

Where an [embedded agent](embedded-agent.md) logs, and how to see what it's doing: its admin and debug endpoints,
its dashboard, its log, and the proxy's view of it.

## Logging

Your application configures the agent's logging. The agent logs through SLF4J, and brings neither an SLF4J provider
nor a configuration, so its loggers go wherever your application's do:

- **With Logback,** your own `logback.xml` (or, in Spring Boot, `logback-spring.xml` and the `logging.level`
  properties) applies to the agent's loggers as it does to the rest. With no configuration at all, Logback logs
  everything at DEBUG, gRPC and Netty included.
- **With another provider,** such as Log4j2, its configuration applies to them instead. The agent's `logLevel` sets a
  level only through Logback: with another provider, the agent logs a warning and leaves the level to its
  configuration.
- **With none,** SLF4J prints a warning that it found no provider, and nothing logs, the agent included. Add one, such
  as `ch.qos.logback:logback-classic`, at runtime.

The standalone agent's configuration sets `io.grpc.internal` to WARN, so that only gRPC's internal warnings and errors
reach its log; you may want the same:

```xml
<logger name="io.grpc.internal" level="WARN"/>
```

In Spring Boot, set `logging.level.io.grpc.internal=WARN` instead.

## Admin and Debug Endpoints

Start by turning on the agent's admin endpoints and its `/debug` page, which are off unless asked for. The simplest
way is the app's environment, which the agent reads itself, with no change to the code:

```bash
ADMIN_ENABLED=true   # the admin endpoints, on port 8093 (ADMIN_PORT for another)
DEBUG_ENABLED=true   # adds /debug
```

They take effect at the app's next start, and override the code, so a deployed app turns them on, and off once the
agent works, with a restart and no new build. In code, `setting("admin.enabled", true)`, `setting("admin.port", 8093)`,
and `setting("admin.debugEnabled", true)` set them, since the builder has no method for them; in a config file or a
`Config`, they're `agent.admin.enabled`, `agent.admin.port`, and `agent.admin.debugEnabled`.

The agent then serves these on its admin port, as the standalone agent does:

| Path           | Shows                                                                                                                                                                                                          |
|:---------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `/ping`        | `pong`, while the agent is running                                                                                                                                                                             |
| `/version`     | The agent's version, release date, and build time, as JSON                                                                                                                                                     |
| `/healthcheck` | The agent's health checks, as JSON: `all_services_healthy`, `scrape_request_backlog_check` (scrape requests waiting for the agent), `thread_deadlock`, `metrics_service` when its metrics port is on, and `dashboard_service` when its dashboard is on |
| `/threaddump`  | A dump of the JVM's threads, your application's as well as the agent's                                                                                                                                         |
| `/debug`       | The agent's name, its ID (empty while it isn't connected), the proxy it's connected to or trying, its uptime, its admin and metrics services, each path with the URL it scrapes, credentials masked, and the paths discovery registered or skipped |

`/healthcheck` stays healthy while the agent can't reach a proxy, since it reports the agent's own state, not its
connection: [`EmbeddedAgentInfo`](embedded-api.md#embeddedagentinfo)'s `isConnected` and `proxyEndpoint` tell you that.

Turn them off once the agent works. The admin port answers on every interface, without authentication, and
`/threaddump` shows your application's threads too, so while they're on, keep the port on an internal network, as for a
standalone agent (see [Production](production.md)). It must be one your application doesn't use: a port in use makes
`start()` throw `IllegalStateException`. The [Ktor Demo](ktor-demo.md#debugging-the-agent)'s coffee shop turns
them on, and its tour shows each one.

## The Agent's Dashboard

The agent's own [dashboard](agent-dashboard.md) shows what the admin endpoints and the proxy's dashboard can't: whether
the agent is connected, and if not, the last error from each proxy endpoint, and each path, with the proxy's reason for
refusing one. `DASHBOARD_ENABLED=true` in the app's environment turns it on, on port 8095 (`DASHBOARD_PORT` for
another), and `dashboard(port = 8095, host = "127.0.0.1")` does it in code. Like the admin port, it has no login unless
one is set, so keep it on an internal network or on `127.0.0.1`.

## Logs

`setting("logLevel", "debug")`, or `AGENT_LOG_LEVEL=debug`, logs the agent's `io.prometheus` loggers at DEBUG, and
leaves your application's own logging alone (see [Logging](#logging)), where a standalone agent's sets the root
logger's level. Even at INFO, the agent logs its settings as it
starts, such as `adminEnabled: true`, after the environment variables have taken precedence, so its startup log shows
whether one overrode the builder.

## On the Agents Dashboard

The [Agents dashboard](grafana.md#dashboards) the project ships shows an embedded agent like any other: its
connections to the proxy, its scrapes and their failures, their latency, and its backlog. It reads them from the
agent's own metrics, which `agentMetricsAt(path)` serves through the proxy, with no port opened for them (see
[The Agent's Own Metrics](embedded-agent.md#the-agents-own-metrics)):

=== "Kotlin"

    ```kotlin
    embeddedAgent {
      // The agent's other settings
      agentMetricsAt(path = "my_app_agent_metrics")
    }.startIfConfigured()
    ```

=== "Java"

    ```java
    EmbeddedAgentConfig.builder()
        // The agent's other settings
        .agentMetricsAt("my_app_agent_metrics")
        .build()
        .startIfConfigured();
    ```

Then give the path a Prometheus job of its own, since the dashboard tells agents apart by `job`. The
[Ktor Demo](ktor-demo.md) does this for its coffee shop's agent, and its Grafana links to the dashboard.

## The Proxy's View

The proxy's [dashboard](web-dashboard.md) shows whether the agent registered, the paths it holds, and each path's
recent scrapes, and `curl -i http://<proxy-host>:8080/<path>` scrapes a path through the proxy without Prometheus.
[Troubleshooting](troubleshooting.md) says what each status code and outcome means.
