---
icon: lucide/lock
---

# TLS Setup

## Requirements

### TLS Without Mutual Authentication

Server-side TLS authenticates the proxy to the agent:

| Component | Required Files |
|:----------|:---------------|
| **Proxy** | `certChainFilePath` (server cert), `privateKeyFilePath` (server key) |
| **Agent** | `trustCertCollectionFilePath` (CA cert) |

### TLS With Mutual Authentication

Both sides authenticate each other:

| Component | Required Files |
|:----------|:---------------|
| **Proxy** | `certChainFilePath`, `privateKeyFilePath`, `trustCertCollectionFilePath` |
| **Agent** | `certChainFilePath`, `privateKeyFilePath`, `trustCertCollectionFilePath` |

## Configuration

=== "CLI (No Mutual Auth)"

    ```bash
    --8<-- "TlsExamples.txt:tls-no-mutual-proxy-cli"
    ```

    ```bash
    --8<-- "TlsExamples.txt:tls-no-mutual-agent-cli"
    ```

=== "CLI (Mutual Auth)"

    ```bash
    --8<-- "TlsExamples.txt:tls-mutual-proxy-cli"
    ```

    ```bash
    --8<-- "TlsExamples.txt:tls-mutual-agent-cli"
    ```

=== "Config File (No Mutual Auth)"

    ```hocon
    --8<-- "TlsExamples.txt:tls-no-mutual-conf"
    ```

=== "Config File (Mutual Auth)"

    ```hocon
    --8<-- "TlsExamples.txt:tls-mutual-conf"
    ```

## Using the Included Test Certificates

The repository includes test certificates for development:

```bash
# Proxy with TLS (no mutual auth) using included certs
java -jar prometheus-proxy.jar --config examples/tls-no-mutual-auth.conf

# Agent with TLS (no mutual auth) using included certs
java -jar prometheus-agent.jar --config examples/tls-no-mutual-auth.conf
```

For mutual auth:

```bash
java -jar prometheus-proxy.jar --config examples/tls-with-mutual-auth.conf
java -jar prometheus-agent.jar --config examples/tls-with-mutual-auth.conf
```

!!! warning "Test certificates only"

    The certificates in `testing/certs/` are for development and testing only.
    Generate your own certificates for production deployments.

## TLS with Docker

Mount certificate files into the container:

```bash
--8<-- "DockerExamples.txt:docker-tls"
```

## Override Authority

For testing scenarios where the server certificate's CN doesn't match the hostname:

```hocon
agent.tls.overrideAuthority = "expected.hostname.com"
```

Or via CLI:

```bash
java -jar prometheus-agent.jar --override expected.hostname.com --config agent.conf
```

## Example Config Files

The repository includes complete TLS configuration examples:

| File | Description |
|:-----|:------------|
| [`examples/tls-no-mutual-auth.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/tls-no-mutual-auth.conf) | Server-side TLS only |
| [`examples/tls-with-mutual-auth.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/tls-with-mutual-auth.conf) | Mutual TLS authentication |

## Setting Up TLS

For detailed instructions on creating TLS certificates for gRPC, see the
[gRPC TLS documentation](https://github.com/grpc/grpc-java/tree/master/examples/example-tls).
