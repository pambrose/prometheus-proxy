#!/bin/sh

docker run --rm -p 8083:8083 \
        -v /Users/pambrose/git/prometheus-proxy/agent.yml:/prometheus-proxy/agent.yml \
        pambrose/prometheus-agent:1.0.0