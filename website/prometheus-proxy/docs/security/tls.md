---
icon: lucide/lock
---

# TLS Setup

## Requirements

### TLS Without Mutual Authentication

Server-side TLS authenticates the proxy to the agent:

| Component | Required Files                                                       |
|:----------|:---------------------------------------------------------------------|
| **Proxy** | `certChainFilePath` (server cert), `privateKeyFilePath` (server key) |
| **Agent** | `trustCertCollectionFilePath` (CA cert)                              |

### TLS With Mutual Authentication

Both sides authenticate each other:

| Component | Required Files                                                           |
|:----------|:-------------------------------------------------------------------------|
| **Proxy** | `certChainFilePath`, `privateKeyFilePath`, `trustCertCollectionFilePath` |
| **Agent** | `certChainFilePath`, `privateKeyFilePath`, `trustCertCollectionFilePath` |

Each file can be given as its PEM text instead, in a setting or an environment variable; see
[Certificates as Environment Variables](#certificates-as-environment-variables).

!!! note "TLS provider"

    Since 4.1.0 the fat JARs and the Docker images use OpenSSL (BoringSSL) for TLS on Linux (x86_64,
    aarch_64), macOS (x86_64, aarch_64), and Windows (x86_64), and fall back to the JDK's TLS elsewhere.
    Certificates, keys, and the TLS options are used as before, but anything that differs between the two
    providers, such as the default cipher suites, now follows BoringSSL.

## Creating Certificates

For a deployment, create a private CA and sign the proxy's certificate with it, and for mutual TLS each
agent's too. With OpenSSL, or the LibreSSL that macOS ships:

```bash
PROXY_HOST=proxy.example.com   # the name agents use to reach the proxy

# A private CA, which both sides trust
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out ca.key
openssl req -x509 -new -key ca.key -sha256 -days 3650 -subj "/CN=prometheus-proxy CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" -out ca.crt

# The proxy's certificate, for PROXY_HOST
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out server.key
openssl req -new -key server.key -subj "/CN=$PROXY_HOST" -out server.csr
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -sha256 -days 825 \
  -extfile <(printf 'subjectAltName=DNS:%s\nextendedKeyUsage=serverAuth\n' "$PROXY_HOST") -out server.crt

# An agent's certificate, for mutual TLS
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out client.key
openssl req -new -key client.key -subj "/CN=agent-1" -out client.csr
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial -sha256 -days 825 \
  -extfile <(printf 'extendedKeyUsage=clientAuth\n') -out client.crt
```

The proxy takes `server.crt` and `server.key`, and for mutual TLS `ca.crt`; each agent takes `ca.crt`, and for
mutual TLS `client.crt` and `client.key`, as in [Configuration](#configuration) below. Repeat the last three commands
with another name for each further agent.

`PROXY_HOST` must be the name agents dial, in `--proxy` or `agent.proxy.hostname`: the agent checks it against the
certificate's subject alternative name. For an IP address, write `IP:%s` in place of `DNS:%s`. In an HA pair, sign
every proxy's certificate with the same CA, since an agent uses one trust store for all its endpoints. `ca.key` signs
every certificate, so keep it off the proxy and agent hosts.

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

## Certificates as Environment Variables

Since 4.6.0, each certificate and key can be given as its PEM text rather than as a file. This suits a platform that
passes secrets as environment variables, such as Railway, DigitalOcean, Cloud Run, or ECS, and the Docker images,
which have no shell to write a variable out to a file:

| File setting                  | PEM setting              | PEM environment variable    |
|:------------------------------|:-------------------------|:----------------------------|
| `certChainFilePath`           | `certChainPem`           | `CERT_CHAIN_PEM`            |
| `privateKeyFilePath`          | `privateKeyPem`          | `PRIVATE_KEY_PEM`           |
| `trustCertCollectionFilePath` | `trustCertCollectionPem` | `TRUST_CERT_COLLECTION_PEM` |

The settings sit under `proxy.tls` and `agent.tls`, beside the file settings, and the variables apply to whichever of
the two reads them. A value can be:

- the PEM text, line breaks and all, as `"$(cat server.key)"` gives it;
- its base64, on one line, for a variable field that takes one line: `base64 < server.crt | tr -d '\n'`;
- the PEM on one line, with each line break written as `\n`.

Give each piece as a file or as PEM, not both: the proxy and the agent refuse a file and a PEM for the same piece. As
with files, a certificate chain and a private key turn TLS on, and a trust collection alone turns it on for an agent.
Only whether a PEM setting is set is logged, never its value, and there are no command-line options for them, so a
key never shows in a process listing. An [embedded agent](../embedded-agent.md#configuring-in-code) can take them in
a config it builds in memory.

## TLS with Docker

Mount certificate files into the container:

```bash
--8<-- "DockerExamples.txt:docker-tls"
```

Or give them as [environment variables](#certificates-as-environment-variables), with no file mounted:

```bash
--8<-- "DockerExamples.txt:docker-tls-pem"
```

## Override Authority

For testing, when the proxy's certificate doesn't list the host name the agent dials among its subject alternative
names:

```hocon
agent.tls.overrideAuthority = "expected.hostname.com"
```

Or via CLI:

```bash
java -jar prometheus-agent.jar --override expected.hostname.com --config agent.conf
```

## Example Config Files

The repository includes complete TLS configuration examples:

| File                                                                                                                                | Description               |
|:------------------------------------------------------------------------------------------------------------------------------------|:--------------------------|
| [`examples/tls-no-mutual-auth.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/tls-no-mutual-auth.conf)     | Server-side TLS only      |
| [`examples/tls-with-mutual-auth.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/tls-with-mutual-auth.conf) | Mutual TLS authentication |

The [gRPC TLS documentation](https://github.com/grpc/grpc-java/tree/master/examples/example-tls) covers TLS for gRPC
in more depth.
