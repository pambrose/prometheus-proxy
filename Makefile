default: build

build:
	mvn -DskipTests=true clean package

tree:
	mvn dependency:tree

jarcheck:
	mvn versions:display-dependency-updates

plugincheck:
	mvn versions:display-plugin-updates

versioncheck: jarcheck plugincheck

docker-build:
	./bin/build-docker-images.sh

docker-push:
	./bin/push-docker-images.sh

docker-all: build docker-build docker-push

run-agent:
	docker run --rm -p 8080:8080 -p 8082:8082 -v /Users/pambrose/Dropbox/prometheus-proxy/agent.yml:/prometheus-proxy/agent.yml pambrose/pambrose/prometheus-agent:1.0.0

run-proxy:
	docker run --rm -p 8080:8080 -p 8081:8081 -p 50051:50051 pambrose/pambrose/prometheus-proxy:1.0.0