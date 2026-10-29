.PHONY: default help stop clean clean-all stubs build tibuild refresh jars \
        tests mini-tests xxl-tests nh-tests ip-tests netty-tests tls-tests lincheck-tests tla-checks check-rules helm-lint zizmor \
        container-tests scaling-tests all-tests ci ci-build regen-certs \
        docker-clean docker-clean-dry \
        all-scaling scaling-paths scaling-agents scaling-payload scaling-consolidated scaling-concurrency scaling-soak \
        coverage coverage-html coverage-xml coverage-log coverage-verify \
        coverage-open coverage-packages coverage-clean reports gh-docs \
        gh-status tsconfig distro docker-push docker-verify homebrew-formulae release release-check _confirm-release \
        tree depends lint detekt detekt-baseline \
        versions versions-all kdocs clean-site check-site upgrade-site build-site site \
        publish-local publish-local-snapshot publish-snapshot publish-maven-central \
        upgrade-wrapper _check-gpg-env _require-version _require-gradle-version _tla-tools

VERSION := $(shell sed -n 's/^version=\(.*\)/\1/p' gradle.properties)
GRADLE_VERSION := $(shell sed -n 's/^gradle-wrapper = "\(.*\)"/\1/p' gradle/libs.versions.toml)

GRADLE := ./gradlew
TSCFG_VERSION := 1.2.5
PLATFORMS := linux/amd64,linux/arm64,linux/s390x,linux/ppc64le
IMAGE_PREFIX := pambrose/prometheus
# A local clone of pambrose/homebrew-tap, which `make homebrew-formulae` writes the formulae into. Each formula's
# source is etc/homebrew/<name>.rb and installs the release jar of the same name.
HOMEBREW_TAP_DIR ?= ../homebrew-tap
HOMEBREW_FORMULAE := prometheus-agent prometheus-proxy
# `make release` publishes only from a clean, up-to-date checkout of this branch whose GitHub CI has passed.
RELEASE_BRANCH ?= master
MAVEN_CENTRAL_URL := https://repo1.maven.org/maven2/com/pambrose/prometheus-proxy
# The files step 2 of docs/RELEASE.md bumps to the new version; release-check fails if one doesn't mention VERSION, or
# still has the previous release's version where step 2 bumps it. Keep this list in step with that step.
RELEASE_VERSION_FILES := README.md llms.txt etc/compose/proxy.yml \
	charts/prometheus-proxy/Chart.yaml charts/prometheus-agent/Chart.yaml \
	website/prometheus-proxy/docs/getting-started.md website/prometheus-proxy/docs/index.md \
	website/prometheus-proxy/docs/docker.md src/test/kotlin/website/DockerExamples.txt \
	src/test/kotlin/website/EmbeddedAgentExamples.txt src/test/kotlin/website/KubernetesExamples.txt \
	docs/RELEASE.md .claude/skills/publishing-release/SKILL.md
# The CI jobs that must have passed for the release commit, as workflow-file/job-name, separated by semicolons since
# job names can hold spaces. make release itself runs only the tests and the distro build, so these are what cover
# the coverage floors, TLA+, the alerting rules, the Helm charts, the docs site build, the container suite, and the
# image and workflow scans. The site build is here because make release ends by deploying the site, so a commit whose
# site doesn't build stops before anything is published. The workflow file tells apart jobs with the same name, such
# as ci.yml's and docs.yml's build. Renaming one of these jobs, or adding a check a release should wait for, means
# updating this list.
RELEASE_REQUIRED_JOBS := ci.yml/build;ci.yml/tla;ci.yml/rules;ci.yml/helm;ci.yml/docs-site;\
	container-tests.yml/container-tests;container-tests.yml/helm-smoke;security.yml/image-scan (proxy);\
	security.yml/image-scan (agent);security.yml/codeql;zizmor.yml/code-scanning
WEBSITE_DIR := website
SITE_DIR := $(WEBSITE_DIR)/prometheus-proxy

# TLA+ model checking (specs/tla). The tools jar is git-ignored, and downloaded when it's missing or doesn't match
# TLA_SHA256; TLC's working states go under build/.
TLA_VERSION := 1.7.4
# SHA-256 of that release's tla2tools.jar, recorded when it was pinned (GitHub publishes no digest for the asset); a
# download that doesn't match is refused. Update it together with TLA_VERSION.
TLA_SHA256 := 936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88
TLA_DIR := specs/tla
TLA_JAR := $(TLA_DIR)/tla2tools.jar
TLC = cd $(TLA_DIR) && java -XX:+UseParallelGC -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -cleanup

# The tool images below are pinned by digest as well as tag, so a re-pushed tag can't change what make and CI run
# without a diff here. Each digest is the image's multi-platform index (`docker buildx imagetools inspect <image:tag>`
# prints it); update the tag and digest together.

# The Prometheus image whose promtool checks grafana/alerts.yml. Keep its version in step with
# ContainerTestSupport.PROMETHEUS_IMAGE, the Prometheus the container suite scrapes through.
PROMETHEUS_IMAGE := prom/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0

# Helm chart checks (charts/): lint each chart with every values file in its ci/ directory, then validate the rendered
# manifests against the Kubernetes schemas and, for the ServiceMonitor, the Prometheus Operator CRD's.
HELM_IMAGE := alpine/helm:3.22.0@sha256:70ed3f7b02ab2eee12aa520ad6870305db8a82516b07aff62f5656c657a26e2a
KUBECONFORM_IMAGE := ghcr.io/yannh/kubeconform:v0.8.0@sha256:faffaf43f95aa6425306e1ab8d6fcad72acb9049158f38e574c085ea1ec0f64e
KUBERNETES_VERSION := 1.30.0
# The CRD schemas come from a fixed commit of datreeio's catalog (2026-10-25), not its main branch, which changed
# them without a diff here.
CRD_SCHEMAS_COMMIT := 3eb90d47aba8d61919d4419dd38301536e32a94b
CRD_SCHEMAS := https://raw.githubusercontent.com/datreeio/CRDs-catalog/$(CRD_SCHEMAS_COMMIT)/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json
CHARTS := prometheus-proxy prometheus-agent

# The jobs of the CI workflow (.github/workflows/ci.yml) that `make ci` runs, quickest first; build-site is its docs-site
# job, and ci-build its build job. Keep the list, and ci-build's steps, in step with that workflow.
CI_JOBS := tla-checks check-rules helm-lint build-site ci-build

# zizmor audits the GitHub Actions workflows and dependabot.yml. The same version as .github/workflows/zizmor.yml, with
# the digest zizmor-action pins for it (its support/versions file); update the two together.
ZIZMOR_IMAGE := ghcr.io/zizmorcore/zizmor:1.31.1@sha256:42d409dbb851d4c5d35a058cf042b828bea1e3f2884378c526eb0974780e18d9

# Banner printed at the start of every scaling target, so a long `all-scaling` run makes it obvious
# which preset is executing at any moment (they look alike in Gradle's output otherwise).
SCALING_BANNER = printf '\n\033[1;36m==> make %s\033[0m -- %s\n\n'

# SCALE_* knobs honored by `scaling-tests`. Single source of truth: used both to forward the
# non-empty ones to Gradle and to render them in the banner.
SCALE_VARS = SCALE_AGENTS SCALE_ENDPOINTS_PER_AGENT SCALE_SERIES_PER_ENDPOINT \
             SCALE_CONSOLIDATED_AGENTS SCALE_CONCURRENT SCALE_CONCURRENCY_LIMIT TEST_MAX_HEAP_SIZE
SCALE_SETTINGS = $(strip $(foreach v,$(SCALE_VARS),$(if $($(v)),$(v)=$($(v)))))

GPG_ENV = \
	ORG_GRADLE_PROJECT_signingInMemoryKey="$$(gpg --armor --export-secret-keys "$$GPG_SIGNING_KEY_ID")" \
	ORG_GRADLE_PROJECT_signingInMemoryKeyId="$$GPG_SIGNING_KEY_ID" \
	ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$$(security find-generic-password -a "gpg-signing" -s "gradle-signing-password" -w)"

default: help

help:  ## Show this help (list of targets)
	@awk 'BEGIN {FS = ":.*?## "; printf "Usage: make <target>\n\nTargets:\n"} \
		/^[a-zA-Z0-9_-]+:.*?## / {printf "  \033[36m%-24s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

stop:  ## Stop the running Gradle daemon
	$(GRADLE) --stop

clean:  ## Remove Gradle build outputs
	$(GRADLE) clean

clean-all: clean clean-site  ## clean + remove .gradle cache and docs site
	rm -rf .gradle

stubs:  ## Regenerate gRPC/protobuf stubs
	$(GRADLE) generateProto

# The protobuf plugin runs generateProto before compiling, so the build targets don't name it; `make stubs` still
# regenerates the stubs on their own.
build:  ## Clean build without tests
	$(GRADLE) clean build -x test

# `ti*` tasks are contributed by the org.barfuin.gradle.taskinfo plugin;
# `tiTree` prints the task graph for the requested build invocation.
tibuild:  ## Build with taskinfo task tree
	$(GRADLE) clean tiTree build -x test

lint:  ## Run kotlinter and detekt
	$(GRADLE) lintKotlin detekt

detekt:  ## Run detekt static analysis
	$(GRADLE) detekt

detekt-baseline:  ## Refresh detekt baseline
	$(GRADLE) detektBaseline

refresh:  ## Refresh dependencies
	$(GRADLE) --refresh-dependencies

jars:  ## Build the prometheus-{agent,proxy} fat jars
	$(GRADLE) agentJar proxyJar

tests:  ## Run all tests (forces re-execution)
	$(GRADLE) --rerun-tasks check

mini-tests:  ## Run all tests with the MINI harness profile
	$(GRADLE) --rerun-tasks check -PharnessConfig=MINI

xxl-tests:  ## Run all tests with the XXLARGE harness profile
	$(GRADLE) --rerun-tasks check -PharnessConfig=XXLARGE

nh-tests:  ## Run only the non-harness unit tests
	$(GRADLE) test --tests "io.prometheus.agent.*" --tests "io.prometheus.proxy.*" --tests "io.prometheus.common.*" --tests "io.prometheus.misc.*"

ip-tests:  ## Run in-process harness tests
	$(GRADLE) test --tests "io.prometheus.harness.InProcess*"

netty-tests:  ## Run Netty harness tests
	$(GRADLE) test --tests "io.prometheus.harness.Netty*"

tls-tests:  ## Run TLS harness tests
	$(GRADLE) test --tests "io.prometheus.harness.Tls*"

lincheck-tests:  ## Run the Lincheck concurrency tests (excluded from the default test run)
	$(GRADLE) test -PkotestTags=Lincheck --tests "*LincheckTest"

# Checks the jar on every run, not only after a download: as a plain file target, the jar an earlier TLA_VERSION left
# stayed in use after TLA_VERSION and TLA_SHA256 moved on, while CI downloaded the new one. A jar that doesn't match,
# or is missing, is fetched again.
_tla-tools:
	@if ! echo "$(TLA_SHA256)  $(TLA_JAR)" | shasum -a 256 -c - >/dev/null 2>&1; then \
		set -e; \
		echo "Downloading the TLA+ tools $(TLA_VERSION) to $(TLA_JAR)"; \
		curl -fsSL -o $(TLA_JAR).tmp https://github.com/tlaplus/tlaplus/releases/download/v$(TLA_VERSION)/tla2tools.jar; \
		echo "$(TLA_SHA256)  $(TLA_JAR).tmp" | shasum -a 256 -c - >/dev/null \
			|| { rm -f $(TLA_JAR).tmp; echo "Error: $(TLA_JAR) checksum mismatch; expected $(TLA_SHA256)" >&2; exit 1; }; \
		mv $(TLA_JAR).tmp $(TLA_JAR); \
	fi

tla-checks: _tla-tools  ## Model-check the TLA+ specs in specs/tla (the quick configs; fetches the pinned TLA+ tools if needed)
	$(TLC) -metadir $(CURDIR)/build/tla/AgentFailover -config AgentFailover.cfg AgentFailover.tla
	$(TLC) -metadir $(CURDIR)/build/tla/ProxyRegistry -config ProxyRegistry.cfg ProxyRegistry.tla

check-rules:  ## Validate the alerting rules in grafana/alerts.yml with promtool (needs Docker)
	docker run --rm -v "$(CURDIR)/grafana:/rules:ro" --entrypoint promtool $(PROMETHEUS_IMAGE) check rules /rules/alerts.yml

helm-lint:  ## Lint the Helm charts and validate their rendered manifests (needs Docker)
	@set -e; for chart in $(CHARTS); do for values in charts/$$chart/ci/*-values.yaml; do \
		echo "==> $$chart with $$(basename $$values)"; \
		docker run --rm -v "$(CURDIR)/charts:/charts:ro" $(HELM_IMAGE) lint --strict /charts/$$chart -f /$$values; \
		manifests=$$(docker run --rm -v "$(CURDIR)/charts:/charts:ro" $(HELM_IMAGE) template ci /charts/$$chart -f /$$values); \
		printf '%s\n' "$$manifests" | docker run --rm -i $(KUBECONFORM_IMAGE) -strict -summary \
			-kubernetes-version $(KUBERNETES_VERSION) -schema-location default -schema-location '$(CRD_SCHEMAS)' -; \
		if [ "$$(basename $$values)" = secret-config-values.yaml ]; then \
			if printf '%s\n' "$$manifests" | grep -q '^kind: ConfigMap'; then \
				echo "Error: $$chart with existingSecret renders a ConfigMap, which would hold its config" >&2; exit 1; fi; \
			printf '%s\n' "$$manifests" | grep -q "secretName: $$chart-config" || \
				{ echo "Error: $$chart with existingSecret doesn't mount the $$chart-config Secret" >&2; exit 1; }; \
		fi; \
	done; done

# The online audits (known-vulnerable and impostor actions) need a GitHub token: GH_TOKEN, else gh's login, else they're
# skipped. The token is passed to the container by name, so it never appears on the command line; an empty GH_TOKEN
# would make zizmor exit with a usage error, so none is passed when there's no token.
zizmor:  ## Audit the GitHub Actions workflows and dependabot.yml with zizmor (needs Docker)
	@GH_TOKEN="$${GH_TOKEN:-$$(gh auth token 2>/dev/null)}"; export GH_TOKEN; \
	docker run --rm -v "$(CURDIR):/workspace:ro" -w /workspace $${GH_TOKEN:+-e GH_TOKEN} $(ZIZMOR_IMAGE) .

container-tests: jars  ## Run the Testcontainers tests (needs Docker)
	@DOCKER_HOST="$$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)"; \
	if [ -z "$$DOCKER_HOST" ]; then \
		echo "Error: could not detect active Docker context. Is Docker running?" >&2; exit 1; \
	fi; \
	echo "Using DOCKER_HOST=$$DOCKER_HOST"; \
	DOCKER_HOST="$$DOCKER_HOST" RUN_CONTAINER_TESTS=true $(GRADLE) test --tests "io.prometheus.containers.*"

all-tests: tests lincheck-tests container-tests  ## Run the full suite: all tests + the container tests

# Runs CI's jobs one after another on the working tree, uncommitted changes included. A failed job doesn't stop the
# rest, as in CI, where they run in parallel; the failed ones are listed at the end. The Codecov upload is left out, and
# so are the pull request's other workflows: `make container-tests` and `make zizmor` run two of them.
ci:  ## Run the CI workflow's jobs locally: TLA+, alerting rules, Helm charts, the docs site, and the build (needs Docker)
	@failed=; for job in $(CI_JOBS); do \
		printf '\n\033[1;36m==> make %s\033[0m\n\n' $$job; \
		$(MAKE) --no-print-directory $$job || failed="$$failed $$job"; \
	done; \
	[ -z "$$failed" ] || { echo "ERROR: CI jobs failed:$$failed" >&2; exit 1; }; \
	echo "CI jobs passed: $(CI_JOBS)"

# ci.yml sets HARNESS_CONFIG=MINI for the whole job, so both Gradle runs see it; were it to differ, the test task would
# be out of date and koverXmlReport would rerun the tests. --continue still writes the coverage report when a coverage
# floor fails, but not when a test fails: the kover tasks depend on the test task, so Gradle skips them.
ci-build:  ## The CI workflow's build job: compile, lint, and detekt, then the tests and coverage floors (MINI harness)
	HARNESS_CONFIG=MINI $(GRADLE) clean build -x test
	HARNESS_CONFIG=MINI $(GRADLE) --continue test koverVerify koverVerifyPerClass koverXmlReport koverLog

docker-clean-dry:  ## Preview what `make docker-clean` would reclaim (removes nothing)
	@./bin/docker-clean-tests.sh --dry-run --cache

docker-clean:  ## Reclaim Docker disk left by the container tests (ARGS="--all" to include cache, base and built images)
	@./bin/docker-clean-tests.sh $(ARGS)

scaling-tests: jars  ## Run the parameter-driven scaling container test (tune via SCALE_* vars; needs Docker)
	@$(SCALING_BANNER) scaling-tests "$(if $(SCALE_SETTINGS),$(SCALE_SETTINGS),built-in defaults)"
	@DOCKER_HOST="$$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)"; \
	if [ -z "$$DOCKER_HOST" ]; then \
		echo "Error: could not detect active Docker context. Is Docker running?" >&2; exit 1; \
	fi; \
	echo "Using DOCKER_HOST=$$DOCKER_HOST"; \
	DOCKER_HOST="$$DOCKER_HOST" RUN_CONTAINER_TESTS=true \
		$(foreach v,$(SCALE_VARS),$(if $($(v)),$(v)="$($(v))" )) \
		$(GRADLE) test --tests "io.prometheus.containers.ContainersScalingTest"

# Curated scaling presets. Each delegates to `scaling-tests` with a SCALE_* combo that hammers a
# different part of the system. They are dev/stress aids — NOT run by `all-tests` or CI (the default
# scaling table already runs via `container-tests`). Containers ≈ 2*agents + 2*consolidated + 1, so
# many-agents presets stress Docker/host while many-endpoints presets stress the proxy cheaply.

# Path/routing-table scaling: few agents, huge fan of paths. Stresses ProxyPathManager registration
# and routing (4 agents x 500 = 2000 paths) with only ~9 containers.
scaling-paths:  ## Scaling preset: 2000 paths across 4 agents (routing-table stress)
	@$(SCALING_BANNER) scaling-paths "2000 paths across 4 agents (routing-table stress)"
	@$(MAKE) --no-print-directory scaling-tests \
		SCALE_AGENTS=4 SCALE_ENDPOINTS_PER_AGENT=500 \
		SCALE_CONCURRENT=true SCALE_CONCURRENCY_LIMIT=100 TEST_MAX_HEAP_SIZE=2g

# Connection/stream scaling: many agents, few paths each. Stresses AgentContextManager, gRPC streams,
# heartbeats, and the transport filter (~81 containers — most likely to hit host/Docker limits first).
scaling-agents:  ## Scaling preset: 40 agents x 2 endpoints (gRPC connection stress)
	@$(SCALING_BANNER) scaling-agents "40 agents x 2 endpoints (gRPC connection stress)"
	@$(MAKE) --no-print-directory scaling-tests \
		SCALE_AGENTS=40 SCALE_ENDPOINTS_PER_AGENT=2 SCALE_CONCURRENT=false

# Chunking/gzip under load: big payloads through several agents at once. Stresses ChunkedContext
# reassembly, CRC validation, and gzip (16 paths x ~50k series; heap-heavy, few containers).
scaling-payload:  ## Scaling preset: 16 paths x 50k series (chunking/gzip stress)
	@$(SCALING_BANNER) scaling-payload "16 paths x 50k series (chunking/gzip stress)"
	@$(MAKE) --no-print-directory scaling-tests \
		SCALE_AGENTS=4 SCALE_ENDPOINTS_PER_AGENT=4 SCALE_SERIES_PER_ENDPOINT=50000 \
		SCALE_CONCURRENT=true TEST_MAX_HEAP_SIZE=4g

# Consolidated fan-out: one path served by many agents. Stresses response merging and agent selection
# on a single path (25 agents merge into one consolidated path; ~53 containers).
scaling-consolidated:  ## Scaling preset: 25-agent consolidated fan-out (response-merge stress)
	@$(SCALING_BANNER) scaling-consolidated "25-agent consolidated fan-out (response-merge stress)"
	@$(MAKE) --no-print-directory scaling-tests \
		SCALE_AGENTS=1 SCALE_ENDPOINTS_PER_AGENT=1 SCALE_CONSOLIDATED_AGENTS=25

# Scrape-correlation concurrency: high concurrent in-flight scrapes. Stresses ScrapeRequestManager
# request/response correlation and timeouts (1800 paths, 150 in flight at once; vary the limit).
scaling-concurrency:  ## Scaling preset: 1800 paths, 150 concurrent (scrape-correlation stress)
	@$(SCALING_BANNER) scaling-concurrency "1800 paths, 150 concurrent (scrape-correlation stress)"
	@$(MAKE) --no-print-directory scaling-tests \
		SCALE_AGENTS=6 SCALE_ENDPOINTS_PER_AGENT=300 \
		SCALE_CONCURRENT=true SCALE_CONCURRENCY_LIMIT=150 TEST_MAX_HEAP_SIZE=3g

# Broad soak: moderate on every axis at once — the most realistic mixed-load shape (501 paths,
# mixed payloads, fan-out, bounded concurrency; ~51 containers).
scaling-soak:  ## Scaling preset: every dimension at once (broad mixed-load soak)
	@$(SCALING_BANNER) scaling-soak "every dimension at once (broad mixed-load soak)"
	@$(MAKE) --no-print-directory scaling-tests \
		SCALE_AGENTS=20 SCALE_ENDPOINTS_PER_AGENT=25 SCALE_SERIES_PER_ENDPOINT=2000 \
		SCALE_CONSOLIDATED_AGENTS=5 SCALE_CONCURRENT=true \
		SCALE_CONCURRENCY_LIMIT=64 TEST_MAX_HEAP_SIZE=6g

all-scaling: scaling-paths scaling-agents scaling-payload scaling-consolidated scaling-concurrency scaling-soak  ## Run all scaling presets

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
	$(GRADLE) koverHtmlReport

coverage-xml:  ## Generate XML coverage report
	$(GRADLE) koverXmlReport

coverage-log:  ## Print coverage % to console
	$(GRADLE) koverLog

coverage-verify:  ## Run kover coverage threshold verification (the total floors and the per-class floor)
	$(GRADLE) koverVerify koverVerifyPerClass

coverage-open: coverage-html  ## Open the HTML coverage report
	open build/reports/kover/html/index.html

coverage-packages: coverage-xml  ## Print per-package coverage from the XML report
	@python3 scripts/coverage_packages.py

coverage-clean:  ## Remove coverage reports and test outputs
	$(GRADLE) cleanTest
	rm -rf build/reports/kover build/kover

# Backwards-compatible alias for the previous `make reports` invocation.
reports: coverage  ## Alias for `coverage`

# A push to master deploys the docs site only once the version it advertises is published (see docs.yml); a run started
# here, as make release does once it has published, deploys whatever the version.
gh-docs:  ## Build and deploy the docs site from master now (runs the docs.yml workflow)
	gh workflow run docs.yml

gh-status:  ## Show recent docs.yml workflow runs
	gh run list --workflow=docs.yml

tsconfig:  ## Regenerate ConfigVals from config/config.conf via tscfg
	java -jar ./config/jars/tscfg-$(TSCFG_VERSION).jar --spec config/config.conf --pn io.prometheus.common --cn ConfigVals --dd src/main/java/io/prometheus/common

distro: build jars  ## Clean build + jars

docker-push: _require-version jars  ## Build and push multi-arch agent/proxy images
	@case "$(VERSION)" in \
		*SNAPSHOT*|*-rc*|*-beta*|*-alpha*) \
			echo "Refusing to push pre-release version $(VERSION) as :latest" >&2; exit 1;; \
	esac
	# prepare multiarch
	docker buildx use buildx 2>/dev/null || docker buildx create --use --name=buildx
	docker buildx build --platform $(PLATFORMS) -f ./etc/docker/proxy.Dockerfile --push -t $(IMAGE_PREFIX)-proxy:latest -t $(IMAGE_PREFIX)-proxy:$(VERSION) .
	docker buildx build --platform $(PLATFORMS) -f ./etc/docker/agent.Dockerfile --push -t $(IMAGE_PREFIX)-agent:latest -t $(IMAGE_PREFIX)-agent:$(VERSION) .

# Hashes the jars attached to the GitHub release rather than build/libs, since those are the files brew downloads.
# Both jars are downloaded before any formula is written, so a missing jar leaves the tap untouched.
homebrew-formulae: _require-version  ## Write the agent and proxy Homebrew formulae for the published release into HOMEBREW_TAP_DIR
	@[ -e "$(HOMEBREW_TAP_DIR)/.git" ] || { \
		echo "ERROR: $(HOMEBREW_TAP_DIR) is not a clone of pambrose/homebrew-tap; clone it there or set HOMEBREW_TAP_DIR" >&2; \
		exit 1; }
	@tmp="$$(mktemp -d)"; trap 'rm -rf "$$tmp"' EXIT; \
	for name in $(HOMEBREW_FORMULAE); do \
		url="https://github.com/pambrose/prometheus-proxy/releases/download/$(VERSION)/$$name.jar"; \
		curl -fsSL -o "$$tmp/$$name.jar" "$$url" \
			|| { echo "ERROR: could not download $$url; publish the $(VERSION) release first" >&2; exit 1; }; \
	done; \
	mkdir -p "$(HOMEBREW_TAP_DIR)/Formula"; \
	for name in $(HOMEBREW_FORMULAE); do \
		sha="$$(shasum -a 256 "$$tmp/$$name.jar" | cut -d' ' -f1)"; \
		sed -e 's/@VERSION@/$(VERSION)/g' -e "s/@SHA256@/$$sha/g" "etc/homebrew/$$name.rb" \
			> "$(HOMEBREW_TAP_DIR)/Formula/$$name.rb"; \
		echo "Wrote $(HOMEBREW_TAP_DIR)/Formula/$$name.rb for $(VERSION) (sha256 $$sha)"; \
	done

docker-verify: _require-version  ## Check both published VERSION images carry every platform in PLATFORMS, and latest matches
	@for c in proxy agent; do \
		image=$(IMAGE_PREFIX)-$$c; \
		out=$$(docker buildx imagetools inspect $$image:$(VERSION)) || exit 1; \
		for platform in $$(echo $(PLATFORMS) | tr ',' ' '); do \
			echo "$$out" | grep -q "Platform: *$$platform" \
				|| { echo "ERROR: $$image:$(VERSION) is missing $$platform" >&2; exit 1; }; \
		done; \
		latest=$$(docker buildx imagetools inspect $$image:latest | grep -m1 '^Digest:'); \
		[ "$$latest" = "$$(echo "$$out" | grep -m1 '^Digest:')" ] \
			|| { echo "ERROR: $$image:latest is not $$image:$(VERSION)" >&2; exit 1; }; \
		echo "$$image:$(VERSION) (and :latest) published for $(PLATFORMS)"; \
	done

# Publishes nothing: it fails unless this checkout is fit to publish VERSION. The local checks run first, then the
# network ones (GitHub's CI checks for HEAD, Maven Central, Docker Hub). A bumped file also fails if it still has the
# previous release's version (the CHANGELOG section below VERSION's) where step 2 bumps it: an image tag or the Maven
# coordinate, a Maven <version>, a chart's version or appVersion, or the backticked examples in docker.md,
# docs/RELEASE.md, and the skill. Historical mentions, such as "Version 4.3.0 (released ...)", don't match. And every
# CI check passing isn't enough, since with [skip ci] or a disabled workflow a lone third-party check can be the whole
# list: each RELEASE_REQUIRED_JOBS job must also have run for HEAD and passed.
release-check: _require-version _check-gpg-env  ## Fail unless this checkout is fit to publish VERSION
	@case "$(VERSION)" in *SNAPSHOT*|*-rc*|*-beta*|*-alpha*) \
		echo "ERROR: $(VERSION) is a pre-release; make release publishes final versions" >&2; exit 1;; esac
	@test -z "$$(git status --porcelain)" \
		|| { echo "ERROR: the working tree is dirty; commit or stash first" >&2; exit 1; }
	@branch=$$(git rev-parse --abbrev-ref HEAD); [ "$$branch" = "$(RELEASE_BRANCH)" ] \
		|| { echo "ERROR: releases are cut from $(RELEASE_BRANCH), not $$branch" >&2; exit 1; }
	@git fetch -q origin $(RELEASE_BRANCH) \
		&& [ "$$(git rev-parse HEAD)" = "$$(git rev-parse origin/$(RELEASE_BRANCH))" ] \
		|| { echo "ERROR: HEAD is not origin/$(RELEASE_BRANCH); pull or push first" >&2; exit 1; }
	@grep -qE '^## \[$(VERSION)\] - [0-9]{4}-[0-9]{2}-[0-9]{2}$$' CHANGELOG.md \
		|| { echo "ERROR: CHANGELOG.md has no dated '## [$(VERSION)] - YYYY-MM-DD' section (step 3 of docs/RELEASE.md)" >&2; exit 1; }
	@awk -v h='## $(VERSION)' '$$0 == h { f = 1; next } f && NF { ok = /^_Released [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]_$$/; exit } END { exit !ok }' RELEASE_NOTES.md \
		|| { echo "ERROR: RELEASE_NOTES.md has no '## $(VERSION)' section starting '_Released YYYY-MM-DD_' (step 3 of docs/RELEASE.md)" >&2; exit 1; }
	@missing=$$(for f in $(RELEASE_VERSION_FILES); do grep -qF '$(VERSION)' "$$f" || echo "  $$f"; done); \
	[ -z "$$missing" ] || { printf 'ERROR: not bumped to $(VERSION) (step 2 of docs/RELEASE.md):\n%s\n' "$$missing" >&2; exit 1; }
	@prev=$$(awk -v h='## [$(VERSION)]' 'f && /^## \[/ { sub(/^## \[/, ""); sub(/\].*/, ""); print; exit } index($$0, h) == 1 { f = 1 }' CHANGELOG.md); \
	[ -n "$$prev" ] || { echo "ERROR: CHANGELOG.md has no release section below [$(VERSION)]" >&2; exit 1; }; \
	p=$$(printf '%s' "$$prev" | sed 's/\./\\./g'); \
	stale=$$(grep -nE "prometheus-(proxy|agent):$$p([^0-9.]|\$$)|<version>$$p</version>|^(version: $$p|appVersion: \"$$p\")\$$|e\.g\., \`$$p\`|\`$$p\` literal" \
		$(RELEASE_VERSION_FILES) | sed 's/^/  /'); \
	[ -z "$$stale" ] || { printf 'ERROR: still at the previous version, %s (step 2 of docs/RELEASE.md):\n%s\n' "$$prev" "$$stale" >&2; exit 1; }
	@tagged=$$(git rev-parse -q --verify "refs/tags/$(VERSION)^{commit}"); \
	[ -z "$$tagged" ] || [ "$$tagged" = "$$(git rev-parse HEAD)" ] \
		|| { echo "ERROR: tag $(VERSION) points at another commit" >&2; exit 1; }
	@docker info >/dev/null 2>&1 \
		|| { echo "ERROR: Docker isn't running; the container tests and docker-push need it" >&2; exit 1; }
	@runs=$$(gh api "repos/{owner}/{repo}/commits/$$(git rev-parse HEAD)/check-runs" --paginate \
		--jq '.check_runs[] | "\(.status) \(.conclusion) \(.name)"') \
		|| { echo "ERROR: could not read HEAD's CI checks from GitHub; is gh logged in?" >&2; exit 1; }; \
	[ -n "$$runs" ] || { echo "ERROR: no CI checks have run for HEAD" >&2; exit 1; }; \
	failed=$$(echo "$$runs" | grep -Ev '^completed (success|skipped|neutral) ' | sed 's/^/  /'); \
	[ -z "$$failed" ] || { printf 'ERROR: CI has not passed for HEAD:\n%s\n' "$$failed" >&2; exit 1; }
	@wfruns=$$(gh api "repos/{owner}/{repo}/actions/runs?head_sha=$$(git rev-parse HEAD)&per_page=100" \
		--jq '.workflow_runs[] | "\(.id) \(.path | sub(".*/"; ""))"') \
		|| { echo "ERROR: could not read HEAD's workflow runs from GitHub; is gh logged in?" >&2; exit 1; }; \
	jobs=$$(echo "$$wfruns" | while read -r id wf; do \
			[ -n "$$id" ] || continue; \
			gh api "repos/{owner}/{repo}/actions/runs/$$id/jobs?per_page=100" \
				--jq ".jobs[] | \"$$wf/\(.name) \(.conclusion)\"" || exit 1; \
		done) \
		|| { echo "ERROR: could not read the jobs of HEAD's workflow runs from GitHub" >&2; exit 1; }; \
	missing=$$(echo '$(RELEASE_REQUIRED_JOBS)' | tr ';' '\n' | sed 's/^ *//' | while read -r job; do \
		echo "$$jobs" | grep -qFx "$$job success" || echo "  $$job"; done); \
	[ -z "$$missing" ] || { printf 'ERROR: required CI jobs have not passed for HEAD:\n%s\n' "$$missing" >&2; exit 1; }
	@! curl -fsI "$(MAVEN_CENTRAL_URL)/$(VERSION)/prometheus-proxy-$(VERSION).pom" >/dev/null 2>&1 \
		|| { echo "ERROR: com.pambrose:prometheus-proxy:$(VERSION) is already on Maven Central; bump the version" >&2; exit 1; }
	@for c in proxy agent; do \
		! docker buildx imagetools inspect $(IMAGE_PREFIX)-$$c:$(VERSION) >/dev/null 2>&1 \
			|| { echo "ERROR: $(IMAGE_PREFIX)-$$c:$(VERSION) is already published; bump the version" >&2; exit 1; }; \
	done
	@echo "Ready to release $(VERSION) from $$(git rev-parse --short HEAD)"

_confirm-release:
	@echo "About to publish $(VERSION). A Maven Central release can't be removed."
	@echo "  Maven Central: com.pambrose:prometheus-proxy:$(VERSION)"
	@echo "  Docker Hub:    $(IMAGE_PREFIX)-proxy and $(IMAGE_PREFIX)-agent, tagged $(VERSION) and latest"
	@echo "                 for $(PLATFORMS)"
	@printf 'Continue? [y/N] ' && read ans && [ "$$ans" = "y" ] || { echo "Nothing published."; exit 1; }

# Each step runs only if the one before it passed, so nothing is published until the checkout passes release-check,
# the full test suite (whose container tests build the images and scrape through them), and the release build.
# Steps 4-6 and 8 of docs/RELEASE.md. Step 8 ends with gh-docs, once the images check out: docs.yml deploys a push only
# once the version it advertises is published, so the merge of the bump left the site at the previous release, and
# this deploys the new one. Everything is published by then, so a failure to start that deploy is reported without
# hiding the next steps. The GitHub release (step 7) and the Homebrew tap (step 9) are printed as next steps. After a
# partial release, finish with the remaining targets: release-check refuses a version already published.
release:  ## Gate, test, build, publish VERSION to Maven Central and Docker Hub (asks first), then deploy the docs
	$(MAKE) release-check
	$(MAKE) all-tests
	$(MAKE) distro
	@$(MAKE) --no-print-directory _confirm-release
	$(MAKE) publish-maven-central
	$(MAKE) docker-push
	$(MAKE) docker-verify
	@if $(MAKE) --no-print-directory gh-docs; then \
		docs="Started the docs site deploy; make gh-status shows the run."; \
	else \
		docs="WARNING: could not start the docs site deploy; run make gh-docs."; \
	fi; \
	prev=$$(git describe --tags --abbrev=0 2>/dev/null); \
	echo; \
	echo "Published $(VERSION) to Maven Central and Docker Hub. $$docs"; \
	echo "Next (steps 7 and 9 of docs/RELEASE.md):"; \
	echo "  1. Create the GitHub release (tag $(VERSION), title v$(VERSION)) with a summary that ends with"; \
	echo "     **Full Changelog**: https://github.com/pambrose/prometheus-proxy/compare/$$prev...$(VERSION)"; \
	echo "     gh release create $(VERSION) --target $$(git rev-parse HEAD) --title v$(VERSION) --notes-file <notes> \\"; \
	echo "       build/libs/prometheus-agent.jar build/libs/prometheus-proxy.jar build/libs/prometheus-proxy-$(VERSION)-sources.jar"; \
	echo "  2. make homebrew-formulae, then commit and push the tap"

tree:  ## Print Gradle dependency tree (quiet)
	$(GRADLE) -q dependencies

depends:  ## Print Gradle dependency report
	$(GRADLE) dependencies

# dependencyUpdates is marked incompatible with the configuration cache (build.gradle.kts), so a run with the cache on
# builds an entry only to discard it, and says so. --no-configuration-cache skips the cache for this run instead.
# build.gradle.kts gives the task a formatter that lists only what is behind; versions-all names the plugin's own plain
# report, which also lists everything up to date.
versions:  ## List the dependencies with newer versions, and any that could not be checked
	$(GRADLE) dependencyUpdates --no-configuration-cache --no-parallel

versions-all:  ## The plugin's full dependency report, up-to-date dependencies included
	$(GRADLE) dependencyUpdates --no-configuration-cache --no-parallel --output-formatter=plain

kdocs:  ## Generate Dokka HTML site
	$(GRADLE) dokkaGeneratePublicationHtml

# Hand upgrades of the docs site's lock wait out the 7 days Dependabot's cooldown does (.github/dependabot.yml): uv
# considers no file uploaded less than SITE_COOLDOWN ago. A cutoff given to uv lock is recorded in uv.lock, and a later
# `uv run --locked` without one, such as CI's docs build, rejects that lock; so a second, plain uv lock drops it,
# keeping the versions the first chose, since uv prefers the locked ones. A locked version uploaded less than
# SITE_COOLDOWN ago, such as a Dependabot security update's, goes back to the newest one before the cutoff; uv lists
# every change.
SITE_COOLDOWN := 7 days

check-site:  ## List what upgrade-site would change in the website dependencies (changes nothing)
	cd $(WEBSITE_DIR) && env -u VIRTUAL_ENV uv lock --upgrade --exclude-newer "$(SITE_COOLDOWN)" --dry-run

upgrade-site:  ## Upgrade the website dependencies, to versions at least 7 days old
	cd $(WEBSITE_DIR) && env -u VIRTUAL_ENV uv lock --upgrade --exclude-newer "$(SITE_COOLDOWN)" \
		&& env -u VIRTUAL_ENV uv lock

# CI's docs-site job, and docs.yml's site build before it adds the KDocs. zensical.toml checks snippet paths, so a
# moved snippet source fails the build.
build-site: clean-site  ## Build the docs site as CI does, from the locked dependencies
	cd $(SITE_DIR) && uv run --locked zensical build --clean

clean-site:  ## Remove generated zensical site and cache
	rm -rf $(SITE_DIR)/site
	rm -rf $(SITE_DIR)/.cache

site: clean-site  ## Serve the docs site locally with zensical
	cd $(SITE_DIR) && uv run zensical serve

publish-local: _require-version  ## Publish artifacts to the local Maven repository
	$(GRADLE) publishToMavenLocal

publish-local-snapshot: _require-version  ## Publish a -SNAPSHOT artifact to the local Maven repository
	$(GRADLE) -PoverrideVersion=$(VERSION)-SNAPSHOT publishToMavenLocal

publish-snapshot: _require-version _check-gpg-env  ## Publish a -SNAPSHOT artifact to Maven Central
	$(GPG_ENV) $(GRADLE) -PoverrideVersion=$(VERSION)-SNAPSHOT publishToMavenCentral

publish-maven-central: _require-version _check-gpg-env  ## Publish a release artifact to Maven Central
	$(GPG_ENV) $(GRADLE) publishAndReleaseToMavenCentral

# Gradle's documented upgrade procedure: the first run rewrites
# gradle-wrapper.properties using the *old* wrapper jar; the second run
# regenerates the wrapper itself with the new version.
upgrade-wrapper: _require-gradle-version  ## Upgrade the Gradle wrapper to the catalog version
	$(GRADLE) wrapper --gradle-version=$(GRADLE_VERSION) --distribution-type=bin
	$(GRADLE) wrapper --gradle-version=$(GRADLE_VERSION) --distribution-type=bin

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
