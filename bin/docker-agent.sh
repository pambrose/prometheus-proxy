#!/bin/sh

docker run --rm -p 8083:8083 -p 8093:8093 \
        -e HOSTNAME=${HOSTNAME} \
        -e AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
        pambrose/prometheus-agent:1.3.2