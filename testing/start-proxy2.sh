#!/usr/bin/env bash

java -jar ../build/libs/prometheus-proxy.jar --config proxy.conf --dashboard --dashboard_port 8095 --agent_port 50052 --port 8081 --metrics_port 9102
