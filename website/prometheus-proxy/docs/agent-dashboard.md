---
icon: lucide/radar
---

# Agent Dashboard

The proxy's [dashboard](web-dashboard.md) shows each connected agent, its paths, and the scrapes the proxy saw. It
can't show what happens on the agent's side of the firewall, and that's where the answer often is. An agent that can't
reach its proxy never appears there. A path the proxy refused never registers. A target the agent scrapes is behind the
firewall too.

The agent's own dashboard shows those. It runs on the agent, on a port of its own, and answers four questions:

- **Is the agent connected?** If not, why not: each proxy endpoint, in failover order, with the last error the agent
  got connecting to it.
- **Is each path registered?** Every path the agent has, configured or discovered, and for one the proxy refused,
  the proxy's reason, whether it's retried, and when.
- **Is each target answering?** Each path's recent scrapes as the agent saw them: the target's status, how long the
  fetch took, and what the agent did with the body. A Scrape now button fetches a target from the agent at once,
  whether or not the proxy has the path.
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
        pathHistorySize = 20        // Recent scrapes kept per path
        scrapeNowEnabled = true     // A Scrape now button on each path
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

Under its URL, a path the proxy has scraped shows the status of its last scrape, then its recent scrapes as a strip of
dots, oldest on the left, green for a success and red for a failure, and the share that succeeded. A registered path the
proxy hasn't scraped says so: Prometheus isn't asking for it.

Select a path to see its URL, its labels, and, while it isn't registered, the proxy's reason, when it was refused, and
when the agent tries it again. A path with a [metric filter](configuration/agent.md#metric-filtering) shows the
filter's rules, how many lines and bytes it has dropped from Prometheus's scrapes, and whether it has ever let a scrape
through unfiltered.

**Recent scrapes** lists the path's last scrapes, newest first, as many as `pathHistorySize` keeps: when each ran, the
target's status, how long the agent's fetch took, and what the agent did with the body. That's its size as the target
sent it, its size after the filter when the filter dropped anything, and whether it was gzipped and sent to the proxy
in chunks. A failed scrape shows the agent's reason, such as a refused connection or its own timeout. A scrape the
proxy's dashboard asked for, with its own Scrape now, is marked `from the proxy's dashboard`.

The agent keeps these only while its dashboard is on, and only for scrapes the proxy asked for; it starts empty when
the agent does.

## Scrape now

A selected path's **Scrape now** button fetches its target from the agent at once and shows the result above the list:
the status, the size before and after the path's filter, its metric families and samples, and the start of the body as
Prometheus would get it, after the filter. It works whether or not the proxy has the path, so the target of a path the
proxy refused, or of an agent that can't connect, can still be checked.

A browser names a path, never a URL: the agent scrapes only a path it has configured, discovered, or registered, at the
URL it has for it. Like the proxy's, the button is limited to four scrapes at once and one per path every five seconds,
and a scrape waits at most ten seconds for its target.

A Scrape now leaves no trace in what the agent reports: not in its `agent_scrape_*` metrics,
`agent_dashboard_scrapes_total`, its filter's counts, or the path's Recent scrapes. The agent logs it, with the address
it came from. Turn the button off with `agent.dashboard.scrapeNowEnabled = false`.

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
