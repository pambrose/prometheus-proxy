VERSION=1.0.0

default: build

build:
	./mvnw -DskipTests=true clean package

config:
	java -jar ./etc/jars/tscfg-0.8.0.jar --spec etc/config/config.conf --pn io.prometheus.common --cn ConfigVals --dd src/main/java/io/prometheus/common

clean:
	./mvnw -DskipTests=true clean

openjdk-base:
	docker build -f ./etc/docker/openjdk-base.df -t=pambrose/prometheus-openjdk-base:${VERSION} .

alpine-base:
	docker build -f ./etc/docker/alpine-base.df -t=pambrose/prometheus-alpine-base:1.0.0 .

docker-build: 
	docker build -f ./etc/docker/proxy.df -t=pambrose/prometheus-proxy:${VERSION} .
	docker build -f ./etc/docker/agent.df -t=pambrose/prometheus-agent:${VERSION} .

docker-push:
	docker push pambrose/prometheus-alpine-base:$VERSION
	docker push pambrose/prometheus-proxy:$VERSION
	docker push pambrose/prometheus-agent:$VERSION

docker-all: clean docker-build docker-push

run-agent:
	docker run --rm -p 8080:8080 -p 8082:8082 -v /Users/pambrose/Dropbox/prometheus-proxy/agent.yml:/prometheus-proxy/agent.yml pambrose/pambrose/prometheus-agent:1.0.0

run-proxy:
	docker run --rm -p 8080:8080 -p 8081:8081 -p 50051:50051 pambrose/pambrose/prometheus-proxy:1.0.0

build-coverage:
	./mvnw clean org.jacoco:jacoco-maven-plugin:prepare-agent package  jacoco:report

report-coverage:
	./mvnw -DrepoToken=${COVERALLS_TOKEN} clean package test jacoco:report coveralls:report

site:
	./mvnw site

tree:
	./mvnw dependency:tree

jarcheck:
	./mvnw versions:display-dependency-updates

plugincheck:
	./mvnw versions:display-plugin-updates

versioncheck: jarcheck plugincheck

