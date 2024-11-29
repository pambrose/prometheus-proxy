VERSION=1.23.0

default: versioncheck

stop:
	./gradlew --stop

clean:
	./gradlew clean

stubs:
	./gradlew generateProto

compile: stubs
	./gradlew build -xtest

build: compile

jars:
	./gradlew agentJar proxyJar

tests:
	./gradlew --rerun-tasks check

reports:
	./gradlew koverMergedHtmlReport

tsconfig:
	java -jar ./etc/jars/tscfg-0.9.997.jar --spec etc/config/config.conf --pn io.prometheus.common --cn ConfigVals --dd src/main/java/io/prometheus/common

distro: clean compile jars

#PLATFORMS := linux/amd64,linux/arm64/v8,linux/s390x,linux/ppc64le
PLATFORMS := linux/amd64,linux/arm64/v8,linux/s390x
IMAGE_PREFIX := pambrose/prometheus

docker-push:
	# prepare multiarch
	docker buildx use buildx 2>/dev/null || docker buildx create --use --name=buildx
	docker buildx build --platform ${PLATFORMS} -f ./etc/docker/proxy.df --push -t ${IMAGE_PREFIX}-proxy:latest -t ${IMAGE_PREFIX}-proxy:${VERSION} .
	docker buildx build --platform ${PLATFORMS} -f ./etc/docker/agent.df --push -t ${IMAGE_PREFIX}-agent:latest -t ${IMAGE_PREFIX}-agent:${VERSION} .

all: distro docker-push

build-coverage:
	./mvnw clean org.jacoco:jacoco-maven-plugin:prepare-agent package  jacoco:report

report-coverage:
	./mvnw -DrepoToken=${COVERALLS_TOKEN} clean package test jacoco:report coveralls:report

sonar:
	./mvnw sonar:sonar -Dsonar.host.url=http://localhost:9000

site:
	./mvnw site

tree:
	./gradlew -q dependencies

depends:
	./gradlew dependencies

lint:
	./gradlew lintKotlinMain
	./gradlew lintKotlinTest

versioncheck:
	./gradlew dependencyUpdates

refresh:
	./gradlew --refresh-dependencies

upgrade-wrapper:
	./gradlew wrapper --gradle-version=8.11.1 --distribution-type=bin
