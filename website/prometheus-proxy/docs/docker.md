---
icon: lucide/container
---

# Docker Usage

Multi-platform images (amd64, arm64, s390x, ppc64le) are published on Docker Hub for every release.

```bash
docker pull pambrose/prometheus-proxy:4.1.0
docker pull pambrose/prometheus-agent:4.1.0
```

## Basic Usage

### Proxy

```bash
--8<-- "DockerExamples.txt:docker-proxy-basic"
```

### Agent with Remote Config

```bash
--8<-- "DockerExamples.txt:docker-agent-basic"
```

## Running in the Background

The examples above run in the foreground: the container stays attached to your terminal and logs there
until you stop it with Ctrl+C, and `--rm` then removes it. To keep a container running in the background,
start it with `--detach` and give it a `--name` to manage it by. `--restart unless-stopped` also starts it
again if it exits or Docker restarts, until you stop it. Docker doesn't allow `--rm` with `--restart`, so
leave `--rm` out:

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
[Running in the Background](#running-in-the-background).

### Proxy with Admin and Metrics

```bash
--8<-- "DockerExamples.txt:docker-proxy-production"
```

### Agent with Local Config File

```bash
--8<-- "DockerExamples.txt:docker-agent-local-config"
```

!!! info "Container WORKDIR"

    The `WORKDIR` of both proxy and agent images is `/app`. Use `/app` as the base
    directory in `--mount` target paths.

## Docker Compose

```yaml
--8<-- "DockerExamples.txt:docker-compose-full"
```

For a stack you can start as-is, [`etc/compose/proxy.yml`](https://github.com/pambrose/prometheus-proxy/blob/master/etc/compose/proxy.yml)
runs a proxy, an agent, and a Prometheus server that scrapes the proxy's and agent's own metrics through the proxy.
From a checkout of the repository:

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

    Use explicit version tags (e.g., `4.1.0`) in production to avoid unexpected upgrades.
