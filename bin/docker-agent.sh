#!/bin/sh

docker run --rm -p 8083:8083 \
        -e AGENT_CONF='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
        pambrose/prometheus-agent:1.0.0