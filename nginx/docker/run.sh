#!/usr/bin/env bash
# Builds and runs the example nginx gRPC reverse proxy from this directory's Dockerfile and nginx.conf.
# Point grpc_pass in nginx.conf at your proxy's agent port before running it.
set -euo pipefail

cd "$(dirname "$0")"
docker build -t prometheus-proxy-nginx .
docker run --rm -p 50440:50440 prometheus-proxy-nginx
