VERSION=1.0.0

default: build

build:
	mvn -DskipTests=true clean package

clean:
	mvn -DskipTests=true clean

openjdk-base:
	docker build -f ./docker/openjdk-base.df -t=pambrose/prometheus-openjdk-base:${VERSION} .

alpine-base:
	docker build -f ./docker/alpine-base.df -t=pambrose/prometheus-alpine-base:1.0.0 .

docker-build: 
	docker build -f ./docker/proxy.df -t=pambrose/prometheus-proxy:${VERSION} .
	docker build -f ./docker/agent.df -t=pambrose/prometheus-agent:${VERSION} .

docker-push:
	docker push pambrose/prometheus-alpine-base:$VERSION
	docker push pambrose/prometheus-proxy:$VERSION
	docker push pambrose/prometheus-agent:$VERSION

docker-all: clean docker-build docker-push

run-agent:
	docker run --rm -p 8080:8080 -p 8082:8082 -v /Users/pambrose/Dropbox/prometheus-proxy/agent.yml:/prometheus-proxy/agent.yml pambrose/pambrose/prometheus-agent:1.0.0

run-proxy:
	docker run --rm -p 8080:8080 -p 8081:8081 -p 50051:50051 pambrose/pambrose/prometheus-proxy:1.0.0

config:
	java -jar ./etc/jars/tscfg-0.8.0.jar --spec etc/config/config.conf --pn com.sudothought.common --cn ConfigVals --dd src/main/java/com/sudothought/common

build-coverage:
	mvn clean package test jacoco:report

report-coverage:
	mvn -DrepoToken=${COVERALLS_TOKEN} clean package test jacoco:report coveralls:report

tree:
	mvn dependency:tree

jarcheck:
	mvn versions:display-dependency-updates

plugincheck:
	mvn versions:display-plugin-updates

versioncheck: jarcheck plugincheck

