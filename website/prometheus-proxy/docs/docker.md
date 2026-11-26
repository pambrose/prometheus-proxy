---
icon: lucide/container
---

# Docker Usage

Multi-platform images (amd64, arm64, s390x, ppc64le) are published on Docker Hub for every release.

```bash
docker pull pambrose/prometheus-proxy:4.7.1
docker pull pambrose/prometheus-agent:4.7.1
```

## Basic Usage

In a container, `localhost` is the container itself, so these put the proxy and the agent on a network they share,
and the agent reaches the proxy by its container name, given in `PROXY_HOSTNAME`. The agent connects over that
network, so the proxy doesn't publish the agents' port, 50051, and publishes its scrape port on the host's `127.0.0.1`
only, for `curl` on the same host. In a real deployment the agent runs on another host, inside the firewall, with
`PROXY_HOSTNAME` set to the proxy's address and no shared network, and the proxy publishes 50051 and requires TLS and
a token, as in [Production Setup](#production-setup).

### Proxy

```bash
--8<-- "DockerExamples.txt:docker-proxy-basic"
```

### Agent with Remote Config

Run the agent in another terminal. Its config,
[`simple.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/simple.conf), serves the proxy's
and the agent's own metrics through the proxy, and fetches the proxy's from the `PROXY_HOSTNAME` host, which is why
the proxy needs `METRICS_ENABLED`:

```bash
--8<-- "DockerExamples.txt:docker-agent-basic"
```

## Running in the Background

The examples above run in the foreground: the container stays attached to your terminal and logs there
until you stop it with Ctrl+C, and `--rm` then removes it. To keep a container running in the background,
start it with `--detach` and give it a `--name` to manage it by. `--restart unless-stopped` also starts it
again if it exits or Docker restarts, until you stop it. Docker doesn't allow `--rm` with `--restart`, so
leave `--rm` out. These run on the network from [Basic Usage](#basic-usage):

```bash
--8<-- "DockerExamples.txt:docker-background"
```

Manage a background container by its name:

```bash
--8<-- "DockerExamples.txt:docker-manage"
```

To move a container to a new release, stop and remove it, then run it again with the new version's tag.

## Production Setup

These run in the background under a restart policy, as described in
[Running in the Background](#running-in-the-background), with the agent on another host. The proxy publishes the
agents' port, 50051, on every interface, so the agent connects over TLS and presents a token. Make the proxy's
certificate and key (`server.crt`, `server.key`) and the token (`agent-token`) as
[Securing the Link](cloud-platforms.md#securing-the-link) does, and copy `server.crt` and `agent-token`, never the
key, to the agent's host. The certificate is issued to the name `prometheus-proxy`, which the agent checks it against
(`OVERRIDE_AUTHORITY`).

The proxy publishes its scrape, metrics, and admin ports on its host's `127.0.0.1` only, as they have no
authentication. For a Prometheus on another host, publish the scrape port on a private address it can reach instead
(`-p 10.0.0.5:8080:8080`), and firewall it; see [Scrape Port Access](security/index.md#scrape-port-access).

### Proxy with Admin and Metrics

```bash
--8<-- "DockerExamples.txt:docker-proxy-production"
```

### Agent with Local Config File

```bash
--8<-- "DockerExamples.txt:docker-agent-local-config"
```

!!! info "Container WORKDIR and user"

    The `WORKDIR` of both proxy and agent images is `/app`. Use `/app` as the base
    directory in `--mount` target paths.

    Both images run as a non-root user with UID and GID 1001, given by number, so a mounted
    config or certificate must be readable by that user. In Kubernetes, `runAsNonRoot` can
    check the user without a `runAsUser`.

## Docker Compose

```yaml
--8<-- "DockerExamples.txt:docker-compose-full"
```

The agent reaches the proxy over the compose network, so the agents' gRPC port, 50051, isn't published, and the
other ports are published on the host's `127.0.0.1` only: nothing in this stack asks for a token or a login, and a port
Docker publishes on every interface gets past the host's firewall, such as ufw or firewalld. For agents on other hosts,
the proxy publishes 50051 and requires a token and TLS, as the compose file in
[Where to Run the Proxy](cloud-platforms.md#where-to-run-the-proxy) does.

For a stack you can start as-is, [`etc/compose/proxy.yml`](https://github.com/pambrose/prometheus-proxy/blob/master/etc/compose/proxy.yml)
runs a proxy, an agent, and a Prometheus server that scrapes the proxy's and agent's own metrics through the proxy,
with its ports published the same way. The agent serves its own at `/agent_metrics`, in-process
(`agent.metrics.proxyPath`), so its metrics port stays off. From a checkout of the repository:

```bash
docker compose -f etc/compose/proxy.yml up
```

Prometheus is then at `http://localhost:9090`, with both targets up on its **Targets** page.

That runs the stack in the foreground until Ctrl+C. To run it in the background instead, add `--detach`;
each service's `restart: unless-stopped` starts it again if it exits or Docker restarts:

```bash
docker compose -f etc/compose/proxy.yml up --detach
docker compose -f etc/compose/proxy.yml logs --follow   # follow the stack's logs
docker compose -f etc/compose/proxy.yml down            # stop and remove the stack
```

## TLS with Docker

Mount your certificate files into the container:

```bash
--8<-- "DockerExamples.txt:docker-tls"
```

Or, on a platform that passes secrets as environment variables, give them as PEM, with no file mounted:

```bash
--8<-- "DockerExamples.txt:docker-tls-pem"
```

See [TLS Setup](security/tls.md) for complete TLS configuration details.

## Environment Variables

```text
--8<-- "DockerExamples.txt:docker-env-vars"
```

## Using the `latest` Tag

The `latest` tag always points to the most recent release:

```bash
docker pull pambrose/prometheus-proxy:latest
docker pull pambrose/prometheus-agent:latest
```

!!! tip "Pin versions in production"

    Use explicit version tags (e.g., `4.7.1`) in production to avoid unexpected upgrades.
