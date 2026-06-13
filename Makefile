.PHONY: default help stop clean clean-all stubs build tibuild refresh jars \
        tests mini-tests nh-tests ip-tests netty-tests tls-tests container-tests all-tests regen-certs \
        coverage coverage-html coverage-xml coverage-log coverage-verify \
        coverage-open coverage-packages coverage-clean reports gh-docs \
        gh-status tsconfig distro docker-push release tree depends lint detekt detekt-baseline \
        versions kdocs clean-site check-site upgrade-site site publish-local publish-local-snapshot publish-snapshot publish-maven-central \
        upgrade-wrapper _check-gpg-env _require-version _require-gradle-version

VERSION := $(shell sed -n 's/^version=\(.*\)/\1/p' gradle.properties)
GRADLE_VERSION := $(shell sed -n 's/^gradle-wrapper = "\(.*\)"/\1/p' gradle/libs.versions.toml)

TSCFG_VERSION := 1.2.5
PLATFORMS := linux/amd64,linux/arm64,linux/s390x,linux/ppc64le
IMAGE_PREFIX := pambrose/prometheus
WEBSITE_DIR := website
SITE_DIR := $(WEBSITE_DIR)/prometheus-proxy

GPG_ENV = \
	ORG_GRADLE_PROJECT_signingInMemoryKey="$$(gpg --armor --export-secret-keys "$$GPG_SIGNING_KEY_ID")" \
	ORG_GRADLE_PROJECT_signingInMemoryKeyId="$$GPG_SIGNING_KEY_ID" \
	ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$$(security find-generic-password -a "gpg-signing" -s "gradle-signing-password" -w)"

default: help

help:  ## Show this help (list of targets)
	@awk 'BEGIN {FS = ":.*?## "; printf "Usage: make <target>\n\nTargets:\n"} \
		/^[a-zA-Z0-9_-]+:.*?## / {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

stop:  ## Stop the running Gradle daemon
	./gradlew --stop

clean:  ## Remove Gradle build outputs
	./gradlew clean

clean-all: clean clean-site  ## clean + remove .gradle cache and docs site
	rm -rf .gradle

stubs:  ## Regenerate gRPC/protobuf stubs
	./gradlew generateProto

build:  ## Clean build without tests
	./gradlew clean generateProto build -x test

# `ti*` tasks are contributed by the org.barfuin.gradle.taskinfo plugin;
# `tiTree` prints the task graph for the requested build invocation.
tibuild:  ## Build with taskinfo task tree
	./gradlew clean generateProto tiTree build -x test

lint:  ## Run kotlinter and detekt
	./gradlew lintKotlin detekt

detekt:  ## Run detekt static analysis
	./gradlew detekt

detekt-baseline:  ## Refresh detekt baseline
	./gradlew detektBaseline

refresh:  ## Refresh dependencies
	./gradlew --refresh-dependencies

jars: stubs  ## Build the prometheus-{agent,proxy} fat jars
	./gradlew agentJar proxyJar

tests:  ## Run all tests (forces re-execution)
	./gradlew --rerun-tasks check

mini-tests:  ## Run all tests with the MINI harness profile
	./gradlew --rerun-tasks check -PharnessConfig=MINI

nh-tests:  ## Run only the non-harness unit tests
	./gradlew test --tests "io.prometheus.agent.*" --tests "io.prometheus.proxy.*" --tests "io.prometheus.common.*" --tests "io.prometheus.misc.*"

ip-tests:  ## Run in-process harness tests
	./gradlew test --tests "io.prometheus.harness.InProcess*"

netty-tests:  ## Run Netty harness tests
	./gradlew test --tests "io.prometheus.harness.Netty*"

tls-tests:  ## Run TLS harness tests
	./gradlew test --tests "io.prometheus.harness.Tls*"

container-tests: jars  ## Run the Testcontainers tests (needs Docker)
	@DOCKER_HOST="$$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)"; \
	if [ -z "$$DOCKER_HOST" ]; then \
		echo "Error: could not detect active Docker context. Is Docker running?" >&2; exit 1; \
	fi; \
	echo "Using DOCKER_HOST=$$DOCKER_HOST"; \
	DOCKER_HOST="$$DOCKER_HOST" RUN_CONTAINER_TESTS=true ./gradlew test --tests "io.prometheus.containers.*"

all-tests: tests container-tests  ## Run the full suite: all tests + the container tests

regen-certs:  ## Regenerate the testing/certs TLS fixtures (CA + server + client; 2048-bit, 100-year validity)
	@command -v openssl >/dev/null 2>&1 || { echo "Error: openssl not found on PATH" >&2; exit 1; }
	@echo "Regenerating TLS fixtures in testing/certs ..."
	@cd testing/certs && \
	openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem -days 36500 \
		-subj "/C=AU/ST=Some-State/O=Internet Widgits Pty Ltd/CN=testca" && \
	openssl req -new -newkey rsa:2048 -nodes -keyout server1.key -out server1.csr \
		-subj "/C=US/ST=Illinois/L=Chicago/O=Example, Co./CN=*.test.google.com" && \
	printf 'subjectAltName=DNS:*.test.google.fr,DNS:waterzooi.test.google.be,DNS:*.test.youtube.com,IP:192.168.1.3\nbasicConstraints=CA:FALSE\n' > server1.ext && \
	openssl x509 -req -in server1.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 36500 \
		-extfile server1.ext -out server1.pem && \
	openssl req -new -newkey rsa:2048 -nodes -keyout client.key -out client.csr \
		-subj "/C=AU/ST=Some-State/O=Internet Widgits Pty Ltd/CN=testclient" && \
	printf 'basicConstraints=CA:FALSE\n' > client.ext && \
	openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 36500 \
		-extfile client.ext -out client.pem && \
	rm -f ca.key ca.srl server1.csr server1.ext client.csr client.ext && \
	openssl verify -CAfile ca.pem server1.pem client.pem && \
	echo "Regenerated ca.pem, server1.pem, server1.key, client.pem, client.key in testing/certs/"

coverage: coverage-html coverage-xml  ## Generate HTML and XML coverage reports

coverage-html:  ## Generate HTML coverage report
	./gradlew koverHtmlReport

coverage-xml:  ## Generate XML coverage report
	./gradlew koverXmlReport

coverage-log:  ## Print coverage % to console
	./gradlew koverLog

coverage-verify:  ## Run kover coverage threshold verification
	./gradlew koverVerify

coverage-open: coverage-html  ## Open the HTML coverage report
	open build/reports/kover/html/index.html

coverage-packages: coverage-xml  ## Print per-package coverage from the XML report
	@python3 scripts/coverage_packages.py

coverage-clean:  ## Remove coverage reports and test outputs
	./gradlew cleanTest
	rm -rf build/reports/kover build/kover

# Backwards-compatible alias for the previous `make reports` invocation.
reports: coverage  ## Alias for `coverage`

gh-docs:  ## Trigger the docs.yml GitHub Actions workflow
	gh workflow run docs.yml

gh-status:  ## Show recent docs.yml workflow runs
	gh run list --workflow=docs.yml

tsconfig:  ## Regenerate ConfigVals from config/config.conf via tscfg
	java -jar ./config/jars/tscfg-$(TSCFG_VERSION).jar --spec config/config.conf --pn io.prometheus.common --cn ConfigVals --dd src/main/java/io/prometheus/common

distro: build jars  ## Clean build + jars

docker-push:  ## Build and push multi-arch agent/proxy images
	@case "$(VERSION)" in \
		*SNAPSHOT*|*-rc*|*-beta*|*-alpha*) \
			echo "Refusing to push pre-release version $(VERSION) as :latest" >&2; exit 1;; \
	esac
	# prepare multiarch
	docker buildx use buildx 2>/dev/null || docker buildx create --use --name=buildx
	docker buildx build --platform $(PLATFORMS) -f ./etc/docker/proxy.df --push -t $(IMAGE_PREFIX)-proxy:latest -t $(IMAGE_PREFIX)-proxy:$(VERSION) .
	docker buildx build --platform $(PLATFORMS) -f ./etc/docker/agent.df --push -t $(IMAGE_PREFIX)-agent:latest -t $(IMAGE_PREFIX)-agent:$(VERSION) .

release: distro docker-push  ## Build distro and push docker images

tree:  ## Print Gradle dependency tree (quiet)
	./gradlew -q dependencies

depends:  ## Print Gradle dependency report
	./gradlew dependencies

versions:  ## Check for newer dependency versions
	./gradlew dependencyUpdates --no-configuration-cache --no-parallel

kdocs:  ## Generate Dokka HTML site
	./gradlew dokkaGeneratePublicationHtml

check-site:  ## Check for outdated website dependencies
	cd $(WEBSITE_DIR) && env -u VIRTUAL_ENV uv lock --upgrade --dry-run

upgrade-site:  ## Upgrade the website dependencies
	cd $(WEBSITE_DIR) && env -u VIRTUAL_ENV uv lock --upgrade

clean-site:  ## Remove generated zensical site and cache
	rm -rf $(SITE_DIR)/site
	rm -rf $(SITE_DIR)/.cache

site: clean-site  ## Serve the docs site locally with zensical
	cd $(SITE_DIR) && uv run --with mkdocs-material zensical serve

publish-local: _require-version  ## Publish artifacts to the local Maven repository
	./gradlew publishToMavenLocal

publish-local-snapshot: _require-version  ## Publish a -SNAPSHOT artifact to the local Maven repository
	./gradlew -PoverrideVersion=$(VERSION)-SNAPSHOT publishToMavenLocal

publish-snapshot: _require-version _check-gpg-env  ## Publish a -SNAPSHOT artifact to Maven Central
	$(GPG_ENV) ./gradlew -PoverrideVersion=$(VERSION)-SNAPSHOT publishToMavenCentral

publish-maven-central: _require-version _check-gpg-env  ## Publish a release artifact to Maven Central
	$(GPG_ENV) ./gradlew publishAndReleaseToMavenCentral

# Gradle's documented upgrade procedure: the first run rewrites
# gradle-wrapper.properties using the *old* wrapper jar; the second run
# regenerates the wrapper itself with the new version.
upgrade-wrapper: _require-gradle-version  ## Upgrade the Gradle wrapper to the catalog version
	./gradlew wrapper --gradle-version=$(GRADLE_VERSION) --distribution-type=bin
	./gradlew wrapper --gradle-version=$(GRADLE_VERSION) --distribution-type=bin

_check-gpg-env:
	@if [ -z "$$GPG_SIGNING_KEY_ID" ]; then \
		echo "ERROR: GPG_SIGNING_KEY_ID is not set" >&2; exit 1; \
	fi
	@if ! gpg --list-secret-keys "$$GPG_SIGNING_KEY_ID" >/dev/null 2>&1; then \
		echo "ERROR: no GPG secret key found for GPG_SIGNING_KEY_ID=$$GPG_SIGNING_KEY_ID" >&2; exit 1; \
	fi
	@if [ -z "$$(security find-generic-password -a 'gpg-signing' -s 'gradle-signing-password' -w 2>/dev/null)" ]; then \
		echo "ERROR: keychain entry 'gradle-signing-password' (account 'gpg-signing') is missing or empty" >&2; exit 1; \
	fi

_require-version:
	@[ -n "$(VERSION)" ] || { echo "ERROR: Could not determine project version from gradle.properties" >&2; exit 1; }

_require-gradle-version:
	@[ -n "$(GRADLE_VERSION)" ] || { echo "ERROR: Could not determine gradle version from gradle/libs.versions.toml" >&2; exit 1; }
