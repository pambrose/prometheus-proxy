VERSION=$(shell awk -F= '/^version[[:space:]]*=/ {gsub(/[[:space:]]/,"",$$2); print $$2; exit}' gradle.properties)

.PHONY: default stop clean stubs build local-build tibuild refresh jars \
        tests nh-tests ip-tests netty-tests tls-tests coverage \
        coverage-xml coverage-log coverage-verify reports gh-docs \
        gh-status tsconfig distro docker-push release tree depends lint \
        versioncheck kdocs clean-docs site publish-local \
        publish-local-snapshot check-gpg-env publish-snapshot \
        publish-maven-central upgrade-wrapper

default: versioncheck

stop:
	./gradlew --stop

clean:
	./gradlew clean

stubs:
	./gradlew generateProto

build: clean stubs
	./gradlew build -xtest

local-build: clean stubs
	./gradlew build -PuseMavenLocal=true

tibuild: clean stubs
	./gradlew tiTree build -xtest

refresh:
	./gradlew --refresh-dependencies

jars: stubs
	./gradlew agentJar proxyJar

tests:
	./gradlew --rerun-tasks check

mini-tests:
	./gradlew --rerun-tasks check -PharnessConfig=MINI

nh-tests:
	./gradlew test --tests "io.prometheus.agent.*" --tests "io.prometheus.proxy.*" --tests "io.prometheus.common.*" --tests "io.prometheus.misc.*"

ip-tests:
	./gradlew test --tests "io.prometheus.harness.InProcess*"

netty-tests:
	./gradlew test --tests "io.prometheus.harness.Netty*"

tls-tests:
	./gradlew test --tests "io.prometheus.harness.Tls*"

coverage:
	./gradlew koverHtmlReport

coverage-xml:
	./gradlew koverXmlReport

coverage-log:
	./gradlew koverLog

coverage-verify:
	./gradlew koverVerify

# Backwards-compatible alias for the previous `make reports` invocation.
reports: coverage

gh-docs:
	gh workflow run docs.yml

gh-status:
	gh run list --workflow=docs.yml

tsconfig:
	java -jar ./config/jars/tscfg-1.2.5.jar --spec config/config.conf --pn io.prometheus.common --cn ConfigVals --dd src/main/java/io/prometheus/common

distro: build
	$(MAKE) jars

PLATFORMS := linux/amd64,linux/arm64,linux/s390x,linux/ppc64le
IMAGE_PREFIX := pambrose/prometheus

docker-push:
	# prepare multiarch
	docker buildx use buildx 2>/dev/null || docker buildx create --use --name=buildx
	docker buildx build --platform ${PLATFORMS} -f ./etc/docker/proxy.df --push -t ${IMAGE_PREFIX}-proxy:latest -t ${IMAGE_PREFIX}-proxy:${VERSION} .
	docker buildx build --platform ${PLATFORMS} -f ./etc/docker/agent.df --push -t ${IMAGE_PREFIX}-agent:latest -t ${IMAGE_PREFIX}-agent:${VERSION} .

release: distro docker-push

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
	ORG_GRADLE_PROJECT_signingInMemoryKeyId="$$GPG_SIGNING_KEY_ID" \
	ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$$(security find-generic-password -a "gpg-signing" -s "gradle-signing-password" -w)"

check-gpg-env:
	@if [ -z "$$GPG_SIGNING_KEY_ID" ]; then \
		echo "Error: GPG_SIGNING_KEY_ID is not set" >&2; exit 1; \
	fi
	@if ! gpg --list-secret-keys "$$GPG_SIGNING_KEY_ID" >/dev/null 2>&1; then \
		echo "Error: no GPG secret key found for GPG_SIGNING_KEY_ID=$$GPG_SIGNING_KEY_ID" >&2; exit 1; \
	fi
	@if [ -z "$$(security find-generic-password -a 'gpg-signing' -s 'gradle-signing-password' -w 2>/dev/null)" ]; then \
		echo "Error: keychain entry 'gradle-signing-password' (account 'gpg-signing') is missing or empty" >&2; exit 1; \
	fi

publish-snapshot: check-gpg-env
	$(GPG_ENV) ./gradlew -PoverrideVersion=$(VERSION)-SNAPSHOT publishToMavenCentral

publish-maven-central: check-gpg-env
	$(GPG_ENV) ./gradlew publishAndReleaseToMavenCentral

upgrade-wrapper:
	./gradlew wrapper --gradle-version=9.5.0 --distribution-type=bin
