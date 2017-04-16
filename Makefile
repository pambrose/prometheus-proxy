VERSION=1.0.0

default: build

build:
	mvn -DskipTests=true clean package

openjdk-base:
	docker build -f ./docker/openjdk-base.df -t=pambrose/prometheus-openjdk-base:${VERSION} .

alpine-base:
	docker build -f ./docker/alpine-base.df -t=pambrose/prometheus-alpine-base:1.0.0 .

docker-build: alpine-base
	docker build -f ./docker/proxy.df -t=pambrose/prometheus-proxy:${VERSION} .
    docker build -f ./docker/agent.df -t=pambrose/prometheus-agent:${VERSION} .

docker-push:
	docker push pambrose/prometheus-alpine-base:$VERSION
	docker push pambrose/prometheus-proxy:$VERSION
	docker push pambrose/prometheus-agent:$VERSION

docker-all: build docker-build docker-push

run-agent:
	docker run --rm -p 8080:8080 -p 8082:8082 -v /Users/pambrose/Dropbox/prometheus-proxy/agent.yml:/prometheus-proxy/agent.yml pambrose/pambrose/prometheus-agent:1.0.0

run-proxy:
	docker run --rm -p 8080:8080 -p 8081:8081 -p 50051:50051 pambrose/pambrose/prometheus-proxy:1.0.0

tree:
	mvn dependency:tree

jarcheck:
	mvn versions:display-dependency-updates

plugincheck:
	mvn versions:display-plugin-updates

versioncheck: jarcheck plugincheck

