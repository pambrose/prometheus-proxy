# prometheus-proxy Helm chart

Deploys the Prometheus Proxy server: Prometheus scrapes it, and it forwards each scrape to the agent that registered
the path, over the gRPC connection that agent opened from inside its firewall. Install the
[prometheus-agent chart](../prometheus-agent) next to the services you want scraped.

```bash
helm install prometheus-proxy ./charts/prometheus-proxy --namespace monitoring --create-namespace
```

Agents inside the cluster connect to `prometheus-proxy.monitoring.svc:50051`. For agents outside it, set
`agentService.enabled=true` to add a LoadBalancer Service for the gRPC port. Prometheus scrapes each path an agent
registers at `http://prometheus-proxy.monitoring.svc:8080/<path>`, or through a ServiceMonitor:

```bash
helm install prometheus-proxy ./charts/prometheus-proxy --namespace monitoring \
  --set serviceMonitor.enabled=true --set 'serviceMonitor.paths={my_app_metrics}'
```

The chart runs one proxy per release: agents register their paths with the proxy they connect to, so a second replica
behind the same Service would answer scrapes for paths it doesn't hold. For high availability, install a second
release and give the agents both proxies (a comma-separated `proxy.hostname`).

## Securing the agent port

Without credentials, any process that can reach the gRPC port can register as an agent. Use one or more of:

- `agentToken.existingSecret`: a shared token, from a Secret you create (key `token`, or set `agentToken.key`)
- `tls.secretName` (with `cert.pem` and `key.pem`), plus `tls.caFile` to require client certificates (mutual TLS)
- per-agent identities, as `proxy.auth` in `config`

See the [security guide](https://pambrose.github.io/prometheus-proxy/security/).

## Values

| Value                                    | Default                                    | Description                                                              |
|------------------------------------------|--------------------------------------------|--------------------------------------------------------------------------|
| `image.repository` / `image.tag`         | `pambrose/prometheus-proxy`                | The image; the tag defaults to the chart's `appVersion`                  |
| `httpPort` / `agentPort`                 | `8080` / `50051`                           | The port Prometheus scrapes, and the gRPC port agents connect to         |
| `config`                                 | `""`                                       | Proxy configuration in HOCON, mounted as `PROXY_CONFIG`                  |
| `existingConfigMap`                      | `""`                                       | A ConfigMap with a `proxy.conf` key, instead of `config`                 |
| `agentToken.existingSecret`              | `""`                                       | Secret holding the shared agent token (`AGENT_TOKEN`)                    |
| `tls.secretName`                         | `""`                                       | Secret with the gRPC port's certificate and key, mounted at `/app/certs` |
| `tls.caFile`                             | `""`                                       | CA in that Secret; setting it requires client certificates               |
| `admin.enabled` / `admin.port`           | `true` / `8092`                            | `/ping` and `/healthcheck`, used by the probes; not on the Service       |
| `metrics.enabled` / `metrics.port`       | `true` / `8082`                            | The proxy's own metrics, on the Service                                  |
| `dashboard.enabled` / `dashboard.port`   | `false` / `8094`                           | The read-only dashboard; not on the Service, so use `port-forward`       |
| `service.type`                           | `ClusterIP`                                | The main Service: HTTP, gRPC, and metrics ports                          |
| `agentService.enabled`                   | `false`                                    | A second Service, gRPC only, for agents outside the cluster              |
| `serviceMonitor.enabled`                 | `false`                                    | A Prometheus Operator ServiceMonitor                                     |
| `serviceMonitor.selfMetrics`             | `true`                                     | Scrape the proxy's own metrics                                           |
| `serviceMonitor.paths`                   | `[]`                                       | Agent paths to scrape through the proxy                                  |
| `javaToolOptions`                        | `-XX:MaxRAMPercentage=75.0`                | `JAVA_TOOL_OPTIONS`                                                      |
| `extraArgs` / `env` / `envFrom`          | `[]`                                       | Extra command-line arguments and environment variables                   |
| `resources`                              | 100m / 256Mi, limit 512Mi                  | Container resources                                                      |
| `podSecurityContext` / `securityContext` | non-root (1001), read-only root filesystem | `/tmp` is an emptyDir                                                    |

`values.yaml` documents the rest (probes, pod labels and annotations, scheduling, extra volumes).
