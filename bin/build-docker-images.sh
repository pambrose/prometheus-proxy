#!/bin/sh

VERSION=1.0.0

cd ~/git/prometheus-proxy

docker build -f ./docker/base.df -t=pambrose/prometheus-base:$VERSION .
docker build -f ./docker/proxy.df -t=pambrose/prometheus-proxy:$VERSION .
docker build -f ./docker/agent.df -t=pambrose/prometheus-agent:$VERSION .
