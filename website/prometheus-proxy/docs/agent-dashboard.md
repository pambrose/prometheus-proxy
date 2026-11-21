---
icon: lucide/radar
---

# Agent Dashboard

The proxy's [dashboard](web-dashboard.md) shows each connected agent, its paths, and the scrapes the proxy saw. It
can't show what happens on the agent's side of the firewall, and that's where the answer often is. An agent that can't
reach its proxy never appears there. A path the proxy refused never registers. A target the agent scrapes is behind the
firewall too.

The agent's own dashboard shows those. It runs on the agent, on a port of its own, and answers three questions:

- **Is the agent connected?** If not, why not: each proxy endpoint, in failover order, with the last error the agent
  got connecting to it.
- **Is each path registered?** Every path the agent has, configured or discovered, and for one the proxy refused,
  the proxy's reason, whether it's retried, and when.
- **What is the agent doing with it?** Each path's metric filter and what it has dropped, the agent's discovery file and
  how its last read went, and the settings the agent resolved.

!!! warning "Off by default, and open until you set a password"

    Like the proxy's, the agent's dashboard has **no TLS**, and **no authentication until you set a password**
    (`agent.dashboard.password`, or `DASHBOARD_PASSWORD`). It shows the agent's paths, target URLs (their passwords
    masked), and proxy endpoints, so treat its port as internal.

    It listens on all interfaces by default, and without a login the agent logs a warning at startup when it does. Set
    `agent.dashboard.host` (or `--dashboard_host`) to `127.0.0.1` to keep it on the agent's host.

## Enabling it

=== "Config file"

    ```hocon
    agent {
      dashboard {
        enabled = true
        port = 8095                 // Its own port, after the agent's admin port (8093)
        path = "dashboard"          // Served at http://<agent>:8095/dashboard
        host = "0.0.0.0"            // "127.0.0.1" keeps it on the agent's host
        refreshIntervalSecs = 2     // How often the page is re-pushed
        username = "admin"          // The login's username, once a password is set
        password = ""               // Requires a login; better set as DASHBOARD_PASSWORD
      }
    }
    ```

=== "Command line"

    ```bash
    java -jar prometheus-agent.jar --config agent.conf --dashboard --dashboard_port 8095
    ```

=== "Environment"

    ```bash
    DASHBOARD_ENABLED=true DASHBOARD_PORT=8095 java -jar prometheus-agent.jar --config agent.conf
    ```

=== "Embedded agent"

    ```kotlin
    embeddedAgent {
      proxy("proxy.example.com")
      scrapeSelf(path = "my_app_metrics", port = 8080)
      dashboard(port = 8095, host = "127.0.0.1")
    }.start()
    ```

Then open `http://<agent-host>:8095/dashboard`.

The flags and the environment variables are the proxy's, read by whichever process reads them, as the admin and
metrics settings are. The other settings, `maxSessions`, `allowedOrigins`, and `allowedHosts`, work as the proxy's do;
see [Exposure](web-dashboard.md#exposure).

## The Paths view

The default view lists every path the agent knows about: those in `agent.pathConfigs`, those its
[discovery file](configuration/agent.md#dynamic-target-discovery) lists, and any registered at runtime. Each row has a
light and a tag for where the path stands with the proxy:

| State      | Light | Meaning                                                                                                  |
|:-----------|:------|:---------------------------------------------------------------------------------------------------------|
| registered | green | The proxy routes the path's scrapes to this agent                                                        |
| retrying   | amber | The proxy refused it for a cause that can clear, such as another agent holding it, or couldn't be asked |
| refused    | red   | The proxy refused it for a cause that can't clear, such as an identity not allowed the path              |
| pending    | gray  | Not registered yet: the agent isn't connected, or hasn't reached it                                      |

Select a path to see its URL, its labels, and, while it isn't registered, the proxy's reason, when it was refused, and
when the agent tries it again. A path with a [metric filter](configuration/agent.md#metric-filtering) shows the
filter's rules, how many lines and bytes it has dropped, and whether it has ever let a scrape through unfiltered.

## The Connection view

The connection's state, `connected`, `connecting`, or `failing`, and the endpoint in use or being tried, marked
`failed over` when it isn't the first. Under it:

- **Proxy endpoints**, in failover order, each with the last error the agent got connecting to it, until a connect to
  it succeeds. A proxy that's down, a wrong port, a refused token, and a TLS mismatch each show here, rather than only
  in the agent's log.
- **Discovery**, when it's on: the file, how often it's read, whether the last read failed, and the entries it skipped,
  missing a path or a URL, or found colliding with a static path or listed twice.
- **Settings**: the scrape timeout and retries, chunking and gzip thresholds, HTTP client limits, and whether the agent
  verifies targets' certificates, as the agent resolved them. A token or a TLS key shows only whether it is set.

## The status bar

The bar along the top shows, on either view:

- whether the page is live, as on the proxy's dashboard;
- the agent's connection state and endpoint;
- how many paths it has, and how many aren't registered;
- its backlog of scrapes waiting to run.

The tab's title counts the problems, each path refused or retrying and a failing connection, so a dashboard in a
background tab still says when something is wrong.
