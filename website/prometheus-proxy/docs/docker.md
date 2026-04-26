---
icon: lucide/container
---

# Docker Usage

Multi-platform images (amd64, arm64, s390x) are published on Docker Hub for every release.

```bash
docker pull pambrose/prometheus-proxy:3.1.1
docker pull pambrose/prometheus-agent:3.1.1
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

## Production Setup

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

    Use explicit version tags (e.g., `3.1.1`) in production to avoid unexpected upgrades.
