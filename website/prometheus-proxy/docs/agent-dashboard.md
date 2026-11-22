---
icon: lucide/radar
---

# Agent Dashboard

The proxy's [dashboard](web-dashboard.md) shows each connected agent, its paths, and the scrapes the proxy saw. It
can't show what happens on the agent's side of the firewall, and that's where the answer often is. An agent that can't
reach its proxy never appears there. A path the proxy refused never registers. A target the agent scrapes is behind the
firewall too.

The agent's own dashboard shows those. It runs on the agent, on a port of its own, and answers five questions:

- **Is the agent connected?** If not, why not: each proxy endpoint, in failover order, with the last error the agent
  got connecting to it.
- **Is each path registered?** Every path the agent has, configured or discovered, and for one the proxy refused,
  the proxy's reason, whether it's retried, and when.
- **Is each target answering?** Each path's recent scrapes as the agent saw them: the target's status, how long the
  fetch took, and what the agent did with the body. A Scrape now button fetches a target from the agent at once,
  whether or not the proxy has the path.
- **What is the agent doing with it?** Each path's metric filter and what it has dropped, the agent's discovery file and
  how its last read went, and the settings the agent resolved.
- **What happened?** The agent's connection made, lost, and failed over, its paths registered and refused, and its
  discovery file failing, in the order they happened.

!!! warning "Off by default, and open until you set a password"

    Like the proxy's, the agent's dashboard has **no TLS**, and **no authentication until you set a password**
    (`agent.dashboard.password`, or `DASHBOARD_PASSWORD`). It shows the agent's paths, target URLs (their passwords
    masked), proxy endpoints, and settings (never a token or password), so treat its port as internal.

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
        activityLogSize = 500       // Lines kept in the Activity view
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

=== "Helm"

    ```bash
    helm upgrade prometheus-agent ./charts/prometheus-agent --namespace monitoring --reuse-values \
      --set dashboard.enabled=true
    kubectl -n monitoring port-forward deploy/prometheus-agent 8095
    ```

    The chart keeps the port off its Service, so you reach it with `kubectl port-forward`;
    `dashboard.login.existingSecret` names a Secret holding the login's password.

Then open `http://<agent-host>:8095/dashboard`.

The flags and the environment variables are the proxy's, read by whichever process reads them, as the admin and
metrics settings are. The other settings, `maxSessions`, `allowedOrigins`, and `allowedHosts`, work as the proxy's do;
see [Exposure](web-dashboard.md#exposure).

## The Paths view

The default view lists every path the agent knows about: those in `agent.pathConfigs`, those its
[discovery file](configuration/agent.md#dynamic-target-discovery) lists, and any registered at runtime. Each row has a
light and a tag for where the path stands with the proxy:

| State      | Light | Tag     | Meaning                                                                                                  |
|:-----------|:------|:--------|:---------------------------------------------------------------------------------------------------------|
| registered | green | none    | The proxy routes the path's scrapes to this agent                                                        |
| retrying   | red   | amber   | The proxy refused it for a cause that can clear, such as another agent holding it, or couldn't be asked, or another agent took it over |
| refused    | red   | red     | The proxy refused it for a cause that can't clear, such as an identity not allowed the path              |
| pending    | red   | neutral | Not registered yet: the agent isn't connected, or hasn't reached it                                      |

Under its URL, a path the proxy has scraped shows the status of its last scrape, then its recent scrapes as a strip of
dots, oldest on the left, green for a success and red for a failure, and the share that succeeded. A registered path the
proxy hasn't scraped says so: Prometheus isn't asking for it.

Select a path to see its URL, its labels, and, while it isn't registered, the proxy's reason, when it was refused, and
when the agent tries it again. A path another agent took over, without consolidated mode, as a redeploy's new version
or a copy of the agent deployed by mistake does, says so, and names that agent: the agent waits while the other serves
the path, and takes it back as soon as no agent does, without reconnecting. A path with a [metric filter](configuration/agent.md#metric-filtering) shows the
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

The agent's settings are on the [Settings view](#the-settings-view).

## The Activity view

What has happened to the agent, newest first: the history the other views can't show, since they show only how
things stand now. It's where you see that a path was refused just after the agent failed over, or that a connection
dropped and came back while nobody was watching.

| Event            | Shown as | When                                                                             |
|:-----------------|:---------|:---------------------------------------------------------------------------------|
| connected        | plain    | The agent connected to a proxy endpoint                                          |
| disconnected     | amber    | A connection ended, with why when an error ended it                              |
| connect failed   | red      | A connect to an endpoint failed; a repeat of the same failure isn't listed again |
| failed over      | amber    | The agent left its first endpoint for the next                                   |
| back to primary  | plain    | The agent went back to its first endpoint                                        |
| registered       | plain    | The proxy accepted a path, configured, discovered, or registered at runtime      |
| refused          | red      | The proxy refused a path, with its reason                                        |
| taken over       | amber    | The proxy gave a path to another agent, which it names                           |
| unregistered     | plain    | A path was removed, such as one that left the discovery file                     |
| discovery failed | red      | The discovery file couldn't be read, so the agent keeps the paths it last read   |
| entries skipped  | red      | The discovery file listed entries without a path or a URL                        |
| not filtered     | red      | A path's metric filter let a scrape through unfiltered, with why                 |

Like events in a burst share a line, such as the agent registering its paths as it connects, counted and naming the
first few paths. The same event again, such as failing over on every retry through a long outage, adds to the line
it repeats. The view keeps `activityLogSize` lines (500), in memory, so a restart empties it. The search filters by
event, path, or detail, and **Problems only** keeps the red lines. The view updates as each line is added.

## The Settings view

Every setting the agent resolved from its flags, environment variables, config, and defaults, as on the proxy's
[Settings layout](web-dashboard.md#the-settings-layout):

| Group | What it shows |
|---|---|
| About | the version, the agent's name, its launch ID, when it started, where its config came from, and the keys of any `-D` overrides |
| Proxy connection | the proxy endpoints in failover order, whether a token is set, consolidated mode, the transport filter, the reconnect pause, gRPC deadlines and keepalive, and the heartbeat |
| Scraping | the scrape timeout and retries, chunking and gzip thresholds, the largest scrape, HTTP client limits and cache, and how targets' certificates are checked |
| Paths | how many paths are configured and filtered, how a refused path is retried, the backlog threshold, and discovery |
| TLS | plaintext, TLS, or mutual TLS, where each certificate and key comes from, and the override authority |
| Dashboard | where it listens, its login's username, its limits, and what it keeps |
| Admin, Metrics, Tracing, Logging | each service's port and paths, the JVM metrics turned on, Zipkin, and the log level |

No secret reaches the page: the agent's token, the trust store's password, the dashboard's password, and TLS material
given as PEM show only as set or not set, and a `-D` override shows only its key. The page is sent the settings once
when it opens, and again after the agent restarts.

## The status bar

The bar along the top shows, on every view:

- which agent the page is, after the logo: its name, as `Agent: agent-east`, the one the Settings view gives
  (`agent.name`, or `Unnamed-` and its host's name when none is set), so the dashboards of several agents are told
  apart at a glance;
- whether the page is live, as on the proxy's dashboard;
- the agent's connection state and endpoint;
- how many paths it has, and how many aren't registered;
- its backlog of scrapes waiting to run.

The tab's title names the agent the same way and counts the problems, each path refused or retrying and a failing
connection, as `(2 problems) Agent: agent-east`, so a dashboard in a background tab still says when something is
wrong. At the bar's end, the theme picker sets the dashboard light or
dark, or **auto**, the default, which follows your system's, as on the proxy's
[dashboard](web-dashboard.md#the-status-bar); the browser remembers the choice for each dashboard.
