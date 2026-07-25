#!/usr/bin/env bash

java -jar ../build/libs/prometheus-proxy.jar --config proxy.conf --dashboard --dashboard_port 8094 --agent_port 50051 --port 8080 --metrics_port 9101
