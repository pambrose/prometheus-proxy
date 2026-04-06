VERSION=$(shell grep '^version\s*=' build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')

default: versioncheck

stop:
	./gradlew --stop

clean:
	./gradlew clean

stubs:
	./gradlew generateProto

build: clean stubs
	./gradlew build -xtest

tibuild: clean stubs
	./gradlew tiTree build -xtest

refresh:
	./gradlew --refresh-dependencies dependencyUpdates

jars:
	./gradlew agentJar proxyJar

tests:
	./gradlew --rerun-tasks check

nh-tests:
	./gradlew test --tests "io.prometheus.agent.*" --tests "io.prometheus.proxy.*" --tests "io.prometheus.common.*" --tests "io.prometheus.misc.*"

ip-tests:
	./gradlew test --tests "io.prometheus.harness.InProcess*"

netty-tests:
	./gradlew test --tests "io.prometheus.harness.Netty*"

tls-tests:
	./gradlew test --tests "io.prometheus.harness.Tls*"

reports:
	./gradlew koverMergedHtmlReport

gh-docs:
	gh workflow run docs.yml

gh-status:
	gh run list --workflow=docs.yml

tsconfig:
	java -jar ./config/jars/tscfg-1.2.5.jar --spec config/config.conf --pn io.prometheus.common --cn ConfigVals --dd src/main/java/io/prometheus/common

distro: build jars

#PLATFORMS := linux/amd64,linux/arm64/v8,linux/s390x,linux/ppc64le
PLATFORMS := linux/amd64,linux/arm64/v8,linux/s390x
IMAGE_PREFIX := pambrose/prometheus

docker-push:
	# prepare multiarch
	docker buildx use buildx 2>/dev/null || docker buildx create --use --name=buildx
	docker buildx build --platform ${PLATFORMS} -f ./etc/docker/proxy.df --push -t ${IMAGE_PREFIX}-proxy:latest -t ${IMAGE_PREFIX}-proxy:${VERSION} .
	docker buildx build --platform ${PLATFORMS} -f ./etc/docker/agent.df --push -t ${IMAGE_PREFIX}-agent:latest -t ${IMAGE_PREFIX}-agent:${VERSION} .
#	docker buildx build --platform ${PLATFORMS} -f ./etc/docker/proxy.df --push -t ${IMAGE_PREFIX}-proxy:${VERSION} .
#	docker buildx build --platform ${PLATFORMS} -f ./etc/docker/agent.df --push -t ${IMAGE_PREFIX}-agent:${VERSION} .

release: distro docker-push

build-coverage:
	./mvnw clean org.jacoco:jacoco-maven-plugin:prepare-agent package  jacoco:report

report-coverage:
	./mvnw -DrepoToken=${COVERALLS_TOKEN} clean package test jacoco:report coveralls:report

sonar:
	./mvnw sonar:sonar -Dsonar.host.url=http://localhost:9000

tree:
	./gradlew -q dependencies

depends:
	./gradlew dependencies

lint:
	./gradlew lintKotlinMain lintKotlinTest

versioncheck:
	./gradlew dependencyUpdates --no-configuration-cache

kdocs:
	./gradlew dokkaGeneratePublicationHtml

clean-docs:
	rm -rf website/prometheus-proxy/site
	rm -rf website/prometheus-proxy/.cache

site: clean-docs
	cd website/prometheus-proxy && uv run --with mkdocs-material zensical serve

publish-local:
	./gradlew publishToMavenLocal

publish-local-snapshot:
	./gradlew -PoverrideVersion=$(VERSION)-SNAPSHOT publishToMavenLocal

GPG_ENV = \
	ORG_GRADLE_PROJECT_signingInMemoryKey="$$(gpg --armor --export-secret-keys $$GPG_SIGNING_KEY_ID)" \
	ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=$$(security find-generic-password -a "gpg-signing" -s "gradle-signing-password" -w)

publish-snapshot:
	$(GPG_ENV) ./gradlew -PoverrideVersion=$(VERSION)-SNAPSHOT publishToMavenCentral

publish-maven-central:
	$(GPG_ENV) ./gradlew publishAndReleaseToMavenCentral

upgrade-wrapper:
	./gradlew wrapper --gradle-version=9.4.1 --distribution-type=bin
