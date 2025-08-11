VERSION=2.2.1-beta4

default: versioncheck

stop:
	./gradlew --stop

clean:
	./gradlew clean

stubs:
	./gradlew generateProto

build: clean stubs
	./gradlew build -xtest

jars:
	./gradlew agentJar proxyJar

tests:
	./gradlew --rerun-tasks check

reports:
	./gradlew koverMergedHtmlReport

tsconfig:
	java -jar ./etc/jars/tscfg-1.2.4.jar --spec etc/config/config.conf --pn io.prometheus.common --cn ConfigVals --dd src/main/java/io/prometheus/common

distro: build jars

#PLATFORMS := linux/amd64,linux/arm64/v8,linux/s390x,linux/ppc64le
PLATFORMS := linux/amd64,linux/arm64/v8,linux/s390x
IMAGE_PREFIX := pambrose/prometheus

docker-push:
	# prepare multiarch
	docker buildx use buildx 2>/dev/null || docker buildx create --use --name=buildx
#	docker buildx build --platform ${PLATFORMS} -f ./etc/docker/proxy.df --push -t ${IMAGE_PREFIX}-proxy:latest -t ${IMAGE_PREFIX}-proxy:${VERSION} .
#	docker buildx build --platform ${PLATFORMS} -f ./etc/docker/agent.df --push -t ${IMAGE_PREFIX}-agent:latest -t ${IMAGE_PREFIX}-agent:${VERSION} .
	docker buildx build --platform ${PLATFORMS} -f ./etc/docker/proxy.df --push -t ${IMAGE_PREFIX}-proxy:${VERSION} .
	docker buildx build --platform ${PLATFORMS} -f ./etc/docker/agent.df --push -t ${IMAGE_PREFIX}-agent:${VERSION} .

release: distro docker-push

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
	./gradlew wrapper --gradle-version=9.0.0 --distribution-type=bin
