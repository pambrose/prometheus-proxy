# prometheus-agent Helm Chart

Deploys a Prometheus Proxy agent: it runs inside a firewall next to the services it scrapes, opens an outbound gRPC
connection to the proxy (see the [prometheus-proxy chart](../prometheus-proxy)), and answers the scrapes the proxy
forwards for the paths it registers. The cluster it runs in needs only egress to the proxy's gRPC port.

```bash
cat > agent-values.yaml <<'EOF'
proxy:
  hostname: proxy.example.com:50051
config: |
  agent {
    pathConfigs: [
      { name: "My app", path: my_app_metrics, url: "http://my-app.default.svc.cluster.local:8080/metrics" }
    ]
  }
EOF
helm install prometheus-agent ./charts/prometheus-agent --namespace monitoring --create-namespace -f agent-values.yaml
```

`proxy.hostname` is required. A comma-separated list of proxies is tried in order, for failover.

## Changing Targets without a Restart

Changing `config` restarts the agent, which interrupts every path it serves. With discovery, the targets live in their
own ConfigMap, which the agent re-reads in place:

```yaml
discovery:
  enabled: true
  targets: |
    paths = [
      { name = "app1", path = "app1_metrics", url = "http://app1.default.svc.cluster.local:9090/metrics" }
    ]
```

Edit the `prometheus-agent-discovery` ConfigMap (`<release>-prometheus-agent-discovery` for a release whose name doesn't
contain `prometheus-agent`; the install's notes name it), or your own, with `discovery.existingConfigMap`, and the agent
picks up the change within `discovery.reconcileIntervalSecs`, after the kubelet updates the mounted file.

## More Than One Replica

> [!WARNING]
> Keep `replicaCount` at 1. Every replica runs the same `config`, so each one registers the same paths and scrapes the
> same targets. With `agent.consolidated = true`, the proxy sends each scrape to every replica and joins their answers,
> so Prometheus gets every series once per replica. It keeps the first sample of each and drops the others, logging
> `Error on ingesting samples with different value but same timestamp` for each target on every scrape and counting
> them in `prometheus_target_scrapes_sample_duplicate_timestamp_total`, while `up` stays 1. Without consolidated mode,
> the replica that registered last serves every path and the others sit idle.

One replica is enough for availability: the Deployment replaces a failed pod, and the new agent registers its paths
again once it starts and connects. Consolidated mode suits agents whose answers differ, such as separate releases that
each scrape their own instance of a service whose series carry an instance label.

## The Agent's Dashboard

The agent's dashboard (`dashboard.enabled`) shows what the proxy's can't: whether the agent is connected, and if not,
the last error from each proxy endpoint; every path, with the proxy's reason for refusing one; each path's recent
scrapes; and what has happened to the agent. It listens on the pod's loopback (`dashboard.host`, `127.0.0.1`), so you
reach it with `kubectl port-forward`, which connects there, and no other pod in the cluster can. Its port isn't on the
Service either:

```bash
helm upgrade prometheus-agent ./charts/prometheus-agent --namespace monitoring --reuse-values \
  --set dashboard.enabled=true
kubectl -n monitoring port-forward deploy/prometheus-agent 8095
```

Then open <http://localhost:8095/dashboard>. To have it ask for a login as well, put a password in a Secret and name it
in `dashboard.login.existingSecret`; the username is `admin` unless you set `dashboard.login.username`:

```bash
kubectl -n monitoring create secret generic prometheus-agent-dashboard --from-literal=password='<a long password>'
helm upgrade prometheus-agent ./charts/prometheus-agent --namespace monitoring --reuse-values \
  --set dashboard.enabled=true --set dashboard.login.existingSecret=prometheus-agent-dashboard
```

Create the Secret with `--from-literal`, not from a file that ends in a newline: the agent refuses a password with a
control character in it. The dashboard serves plain HTTP, so the password is protected only by the port-forward's
tunnel.

To publish the dashboard through a Service of your own instead, set `dashboard.host` to `0.0.0.0`, and require a
login. Any pod in the cluster can then open the dashboard at the pod's address, and the password crosses the pod
network in plain HTTP.

## Values

| Value                                    | Default                                    | Description                                                                        |
|------------------------------------------|--------------------------------------------|------------------------------------------------------------------------------------|
| `image.repository` / `image.tag`         | `pambrose/prometheus-agent`                | The image; the tag defaults to the chart's `appVersion`                            |
| `proxy.hostname`                         | (required)                                 | The proxy's gRPC address, `host:port` (`PROXY_HOSTNAME`)                           |
| `config`                                 | an empty `pathConfigs`                     | Agent configuration in HOCON, mounted as `AGENT_CONFIG`                            |
| `existingConfigMap`                      | `""`                                       | A ConfigMap with an `agent.conf` key, instead of `config`                          |
| `existingSecret`                         | `""`                                       | A Secret with an `agent.conf` key, instead of either; for credentials in URLs      |
| `replicaCount`                           | `1`                                        | Keep it at 1: see [More than one replica](#more-than-one-replica)                  |
| `discovery.enabled`                      | `false`                                    | Re-read a paths list from its own ConfigMap                                        |
| `discovery.targets`                      | `""`                                       | The paths list in HOCON                                                            |
| `discovery.existingConfigMap`            | `""`                                       | A ConfigMap with a `targets.conf` key, instead of `targets`                        |
| `discovery.reconcileIntervalSecs`        | `30`                                       | How often the list is re-read                                                      |
| `agentToken.existingSecret`              | `""`                                       | Secret holding the shared agent token (`AGENT_TOKEN`)                              |
| `tls.secretName`                         | `""`                                       | Secret mounted at `/app/certs`; `tls.caFile` (default `ca.pem`) verifies the proxy |
| `tls.certFile` / `tls.keyFile`           | `""`                                       | A client certificate, for mutual TLS                                               |
| `tls.overrideAuthority`                  | `""`                                       | The name to verify the proxy's certificate against                                 |
| `admin.enabled` / `admin.port`           | `true` / `8093`                            | `/ping` and `/healthcheck`, used by the probes                                     |
| `metrics.enabled` / `metrics.port`       | `true` / `8083`                            | The agent's own metrics                                                            |
| `dashboard.enabled` / `dashboard.port`   | `false` / `8095`                           | The agent's dashboard; not on the Service, so use `port-forward`                   |
| `dashboard.host`                         | `127.0.0.1`                                | Its listen address (`DASHBOARD_HOST`); the pod's loopback keeps it local           |
| `dashboard.login.existingSecret`         | `""`                                       | Secret holding the dashboard's password (`DASHBOARD_PASSWORD`), at `key`           |
| `dashboard.login.username`               | `admin`                                    | The username the dashboard's login takes (`DASHBOARD_USERNAME`)                    |
| `service.enabled`                        | `true`                                     | A Service for the metrics port                                                     |
| `serviceMonitor.enabled`                 | `false`                                    | A Prometheus Operator ServiceMonitor for the agent's own metrics                   |
| `javaToolOptions`                        | `-XX:MaxRAMPercentage=75.0`                | `JAVA_TOOL_OPTIONS`                                                                |
| `extraArgs` / `env` / `envFrom`          | `[]`                                       | Extra command-line arguments and environment variables                             |
| `resources`                              | 100m / 256Mi, limit 512Mi                  | Container resources                                                                |
| `podSecurityContext` / `securityContext` | non-root (1001), read-only root filesystem | `/tmp` is an emptyDir                                                              |

`values.yaml` documents the rest (probes, pod labels and annotations, scheduling, extra volumes).
