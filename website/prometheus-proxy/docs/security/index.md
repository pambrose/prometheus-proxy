---
icon: lucide/shield
---

# Security

## Security Model

Prometheus Proxy is designed to be firewall-friendly:

- The **agent** initiates an *outbound* gRPC connection to the proxy
- **No inbound ports** need to be opened on the firewall
- The proxy accepts connections only from registered agents
- Stale agent connections are automatically cleaned up

## TLS Encryption

Agents connect to the proxy using [gRPC](https://grpc.io), which supports TLS with or without
mutual authentication.

| Mode | Proxy Needs | Agent Needs |
|:-----|:------------|:------------|
| **No TLS** | Nothing | Nothing |
| **TLS (server only)** | Server cert + key | CA cert (trust store) |
| **Mutual TLS** | Server cert + key + CA cert | Client cert + key + CA cert |

See [TLS Setup](tls.md) for detailed configuration instructions.

## Auth Header Forwarding

When Prometheus scrape configurations include `basic_auth` or `bearer_token`, the proxy forwards
the `Authorization` header to the agent over the gRPC channel. The agent then includes this
header when fetching metrics from the target endpoint.

```yaml
--8<-- "PrometheusConfigs.txt:auth-scrape-config"
```

!!! danger "Credentials transmitted in plaintext without TLS"

    Without TLS, the `Authorization` header is transmitted in plaintext between the proxy
    and agent. The proxy logs a warning on the first request that includes an
    `Authorization` header when TLS is not enabled.

    **Always enable TLS when forwarding authentication headers.**

```text
--8<-- "TlsExamples.txt:auth-header-tls"
```

## Scraping HTTPS Endpoints

If the agent needs to scrape HTTPS endpoints with self-signed certificates, you can disable
SSL verification:

```text
--8<-- "TlsExamples.txt:trust-all-x509"
```

!!! warning "Development only"

    Only use `trust_all_x509` in development or testing environments. In production,
    configure proper TLS certificates for your metrics endpoints.
