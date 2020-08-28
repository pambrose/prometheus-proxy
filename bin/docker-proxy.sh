#!/bin/sh

docker run --rm -p 8082:8082 -p 8092:8092 -p 50051:50051 -p 8080:8080 \
  --env PROXY_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
  pambrose/prometheus-proxy:1.9.0
