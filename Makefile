VERSION=1.4.6

default: compile

clean:
	./gradlew clean

compile:
	./gradlew build -xtest

build: compile

jars:
	./gradlew agentJar proxyJar

tests:
	./gradlew check

config:
	java -jar ./etc/jars/tscfg-0.9.94.jar --spec etc/config/config.conf --pn io.prometheus.common --cn ConfigVals --dd src/main/java/io/prometheus/common

distro: clean compile jars

docker-build:
	docker build -f ./etc/docker/proxy.df -t pambrose/prometheus-proxy:${VERSION} .
	docker build -f ./etc/docker/agent.df -t pambrose/prometheus-agent:${VERSION} .

docker-push:
	docker push pambrose/prometheus-proxy:${VERSION}
	docker push pambrose/prometheus-agent:${VERSION}

all: distro docker-build docker-push

build-coverage:
	./mvnw clean org.jacoco:jacoco-maven-plugin:prepare-agent package  jacoco:report

report-coverage:
	./mvnw -DrepoToken=${COVERALLS_TOKEN} clean package test jacoco:report coveralls:report

sonar:
	./mvnw sonar:sonar -Dsonar.host.url=http://localhost:9000

site:
	./mvnw site

tree:
	./mvnw dependency:tree

versioncheck:
	./gradlew dependencyUpdates

