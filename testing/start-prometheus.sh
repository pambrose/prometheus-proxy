#!/usr/bin/env bash

# The same Prometheus version the container tests pin (ContainerTestSupport.PROMETHEUS_IMAGE).
docker run --name prometheus \
	--rm \
	-p 9090:9090 \
	-v "$(pwd)/prometheus.yml:/etc/prometheus/prometheus.yml" \
	prom/prometheus:v3.14.0

