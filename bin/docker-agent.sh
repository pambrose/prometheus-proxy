#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# The version the docs site gives (make site-version): the last release, or, from a version bump until its release is
# out, the next one. Not the gradle.properties version: between releases that's a -SNAPSHOT or the next version.
# Fail fast, as make does when the site doesn't give one version, rather than pass Docker a bad image reference.
VERSION=$(make -s --no-print-directory -C "$SCRIPT_DIR/.." site-version) || exit 1
IMAGE="pambrose/prometheus-agent:${VERSION}"

# Docker Hub has no image of a release that isn't out yet: say so, rather than leave it to docker run's pull error. An
# image Docker has already, or a Docker Hub that can't be reached, goes on to docker run, which runs the one or reports
# the other.
if ! docker image inspect "$IMAGE" >/dev/null 2>&1 &&
  docker manifest inspect "$IMAGE" 2>&1 >/dev/null | grep -q 'no such manifest'; then
  echo "ERROR: $IMAGE isn't on Docker Hub yet: the docs give release $VERSION, which isn't out. make docker-build" \
    "builds prometheus-agent:local from this checkout." >&2
  exit 1
fi

docker run --rm -p 8083:8083 -p 8093:8093 \
  --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
  --env PROXY_HOSTNAME=mymachine.lan \
  "$IMAGE"
