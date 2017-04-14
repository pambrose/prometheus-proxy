#!/bin/sh

VERSION=1.0.0

docker push pambrose/prometheus-base:$VERSION
docker push pambrose/prometheus-proxy:$VERSION
docker push pambrose/prometheus-agent:$VERSION
