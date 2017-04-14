#!/bin/sh

docker run --rm -p 8081:8081 -p 50051:50051 -p 8080:8080 \
        pambrose/prometheus-proxy:1.0.0