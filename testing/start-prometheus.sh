#!/usr/bin/env bash

docker run --name prometheus \
	--rm \
	-p 9090:9090 \
	-v $(pwd)/prometheus.yml:/etc/prometheus/prometheus.yml \
	prom/prometheus

