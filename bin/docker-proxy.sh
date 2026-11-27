#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# The version the docs site gives (make site-version): the last release, or, from a version bump until its release is
# out, the next one. Not the gradle.properties version: between releases that's a -SNAPSHOT or the next version.
# Fail fast, as make does when the site doesn't give one version, rather than pass Docker a bad image reference.
VERSION=$(make -s --no-print-directory -C "$SCRIPT_DIR/.." site-version) || exit 1
IMAGE="pambrose/prometheus-proxy:${VERSION}"

# Docker Hub has no image of a release that isn't out yet: say so, rather than leave it to docker run's pull error. An
# image Docker has already, or a Docker Hub that can't be reached, goes on to docker run, which runs the one or reports
# the other.
if ! docker image inspect "$IMAGE" >/dev/null 2>&1 &&
  docker manifest inspect "$IMAGE" 2>&1 >/dev/null | grep -q 'no such manifest'; then
  echo "ERROR: $IMAGE isn't on Docker Hub yet: the docs give release $VERSION, which isn't out. make docker-build" \
    "builds prometheus-proxy:local from this checkout." >&2
  exit 1
fi

# The proxy and the agent (bin/docker-agent.sh) share a Docker network, as the Docker page's Basic Usage runs them, so
# the agent reaches the proxy by its container name. Every port is published on this machine's 127.0.0.1 only, as
# nothing here asks for a token or a login: the scrape port, the metrics and admin ports examples/simple.conf turns on,
# and the agents' port, for an agent run on this machine. The config is the release's, which the image runs.
NETWORK=prometheus-proxy-net
docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK" >/dev/null || exit 1

docker run --rm --name prometheus-proxy --network "$NETWORK" \
  -p 127.0.0.1:8080:8080 -p 127.0.0.1:8082:8082 -p 127.0.0.1:8092:8092 -p 127.0.0.1:50051:50051 \
  --env PROXY_CONFIG="https://raw.githubusercontent.com/pambrose/prometheus-proxy/${VERSION}/examples/simple.conf" \
  "$IMAGE"
