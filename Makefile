VERSION=$(shell awk -F= '/^version[[:space:]]*=/ {gsub(/[[:space:]]/,"",$$2); print $$2; exit}' gradle.properties)
GRADLE_VERSION=$(shell awk -F\" '/^gradle-wrapper[[:space:]]*=/ {print $$2; exit}' gradle/libs.versions.toml)

.PHONY: default stop clean clean-all stubs build tibuild refresh jars \
        tests nh-tests ip-tests netty-tests tls-tests container-tests coverage \
        coverage-xml coverage-log coverage-verify reports gh-docs \
        gh-status tsconfig distro docker-push release tree depends lint detekt-baseline \
        versioncheck kdocs clean-docs site publish-local \
        publish-local-snapshot check-gpg-env publish-snapshot \
        publish-maven-central upgrade-wrapper

default: versioncheck

stop:
	./gradlew --stop

clean:
	./gradlew clean

clean-all: clean clean-docs
	rm -rf .gradle

stubs:
	./gradlew generateProto

build: clean stubs
	./gradlew build -xtest

tibuild: clean stubs
	./gradlew tiTree build -xtest

lint:
	./gradlew lintKotlinMain lintKotlinTest detekt

detekt-baseline:
	./gradlew detektBaseline

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

container-tests: jars
	@DOCKER_HOST="$$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)"; \
	if [ -z "$$DOCKER_HOST" ]; then \
		echo "Error: could not detect active Docker context. Is Docker running?" >&2; exit 1; \
	fi; \
	echo "Using DOCKER_HOST=$$DOCKER_HOST"; \
	DOCKER_HOST="$$DOCKER_HOST" RUN_CONTAINER_TESTS=true ./gradlew test --tests "io.prometheus.containers.*"

coverage: coverage-html coverage-xml

coverage-html:
	./gradlew koverHtmlReport

coverage-xml:
	./gradlew koverXmlReport

coverage-log:
	./gradlew koverLog

coverage-verify:
	./gradlew koverVerify

coverage-open: coverage-html
	open build/reports/kover/html/index.html

coverage-packages: coverage-xml
	@python3 -c "import xml.etree.ElementTree as ET; \
r = ET.parse('build/reports/kover/report.xml').getroot(); \
pkgs = []; \
[pkgs.append((p.get('name'), int(c.get('covered')), int(c.get('missed')))) \
 for p in r.findall('package') for c in p.findall('counter') if c.get('type') == 'INSTRUCTION']; \
pkgs.sort(key=lambda x: -x[2]); \
print(f\"{'package':<55} {'cov%':>6} {'covered':>9} {'missed':>9} {'total':>9}\"); \
[print(f'{n:<55} {(c/(c+m)*100 if c+m else 0):6.1f} {c:9d} {m:9d} {c+m:9d}') for n,c,m in pkgs]; \
tc=sum(p[1] for p in pkgs); tm=sum(p[2] for p in pkgs); \
print(f'\nOVERALL: {tc/(tc+tm)*100:.2f}% ({tc}/{tc+tm} instructions, {tm} missed)')"

coverage-clean:
	./gradlew cleanAllTests
	rm -rf build/reports/kover build/kover

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
	./gradlew wrapper --gradle-version=$(GRADLE_VERSION) --distribution-type=bin
