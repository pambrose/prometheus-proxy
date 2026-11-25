---
name: publishing-release
description: Use when publishing prometheus-proxy to Maven Central, cutting a release, running a snapshot publish, or bumping the project version — covers the Maven Central coordinates, GPG and Maven Central credential prerequisites, and the version-bump checklist.
---

# Publishing prometheus-proxy

Published to Maven Central as `com.pambrose:prometheus-proxy`, with the Spring Boot starter
(`spring-boot-starter/`) as `com.pambrose:prometheus-proxy-spring-boot-starter` and the Ktor plugin (`ktor-plugin/`)
as `com.pambrose:prometheus-proxy-ktor`, at the same version, which every publish task releases too. No JitPack.

Repository declarations are centralized in `settings.gradle.kts` via `dependencyResolutionManagement(FAIL_ON_PROJECT_REPOS)` and resolve solely from Maven Central.

## Publish targets

Snapshot and Maven Central release Make targets (`publish-snapshot`, `publish-maven-central`) need the GPG signing setup and the Maven Central credentials, and their prerequisites validate both up-front (`make release-check` runs both checks too):

- `make _check-gpg-env`: `GPG_SIGNING_KEY_ID` is set, GPG holds its secret key, and the keychain entry `gradle-signing-password` (account `gpg-signing`) holds its passphrase.
- `make _check-maven-central-env`: the keychain entries `maven-central-username` and `maven-central-password` (account `maven-central`) hold a central.sonatype.com user token's name and password. The targets pass them to Gradle as `ORG_GRADLE_PROJECT_mavenCentralUsername` / `ORG_GRADLE_PROJECT_mavenCentralPassword` in the publishing command's environment only; don't put them in `~/.gradle/gradle.properties`, which every Gradle build on the machine reads. Add each once with `security add-generic-password -a maven-central -s <entry> -U -w`, which asks for the value.

`make release` cuts a release once the version bump and changelogs are merged: it gates with `make release-check` (clean, up-to-date `master` whose GitHub checks passed, including each CI job `RELEASE_REQUIRED_JOBS` lists; both changelogs have a section for the version dated the day it runs, so date them in the last change before `make release`; the bumped files name it and no longer have the previous version where it's bumped, the raw config links and the Quick Start's JAR links included; every demo's `NEEDS_RELEASE_AFTER` is below it; not already on Maven Central or Docker Hub; GPG, the Maven Central credentials, and Docker ready), runs `make all-tests` and `make distro`, asks once, then publishes to Maven Central and Docker Hub, runs `make docker-verify`, pushes the release tag (`make release-tag`), which the docs' `git clone --branch` commands check out, creates the GitHub release on it with the JARs and `RELEASE_NOTES.md`'s section as its notes (`make github-release`; `make github-release-notes` prints them), and deploys the docs site with `make gh-docs` (a push to `master` never deploys the site, so it changes only with a release). The Homebrew tap stays manual; it prints that step. See `docs/RELEASE.md`.

## Bumping the version

When bumping the version, update `version` in `gradle.properties` and every hard-coded `4.7.1` literal (`git grep -n` for the outgoing version):

- `README.md` and `llms.txt` — Docker tag examples + Maven Central dependency block (README's **New Features** paragraph is replaced with the new release's summary)
- `etc/compose/proxy.yml` — proxy and agent image tags
- `examples/cloud-proxy/docker-compose.yml` — proxy image tag
- `charts/prometheus-{proxy,agent}/Chart.yaml` — `version` and `appVersion` (the image tag the charts deploy)
- `website/prometheus-proxy/docs/{getting-started,docker}.md` — Docker pull/run examples
- `website/prometheus-proxy/docs/getting-started.md` — the JAR tab's release page and download links (`releases/tag/…`, `releases/download/…/`)
- `src/test/kotlin/website/{DockerExamples,EmbeddedAgentExamples,KubernetesExamples}.txt` — snippet sources the website's Docker, Embedded Agent, and Kubernetes pages include
- `README.md`, `llms.txt`, `website/prometheus-proxy/docs/grafana.md`, and `src/test/kotlin/website/{DockerExamples,GettingStarted,ConfigExamples}.txt` — the release tag in the `raw.githubusercontent.com` config and dashboard links
- `README.md`, `llms.txt`, `demos/{basics,network,nginx,kubernetes,ktor,spring-boot,plain-kotlin}/README.md`, and `website/prometheus-proxy/docs/{basics-demo,network-demo,nginx-demo,kubernetes-demo,ktor-demo,spring-boot-demo,plain-kotlin-demo,getting-started}.md` — the `git clone --branch` tag the demos and the Helm install check out
- `demos/spring-boot/app/pom.xml` — `prometheus-proxy.version`, the release the Spring Boot demo builds against
- this line, and step 2 of `docs/RELEASE.md`

`RELEASE_VERSION_FILES` in the `Makefile` lists the same files for `make release-check`; keep it in step. Once both changelogs have the new version's section, `make _check-version-files` lists any of them that doesn't name it yet, and any line still at the previous release.

Don't bump `NEEDS_RELEASE_AFTER` in `demos/*/demo.sh`: it's the newest release that lacks what the demo shows, and a demo's `make up` runs the release the docs give once it's after that and its images are on Docker Hub, building from the checkout until then, so it needs no change at a bump or a release. Set it to the latest release on Docker Hub only when a demo comes to use something no release has yet; `make release-check` requires it below the version.

The Homebrew formulae need no edit for a bump: `etc/homebrew/prometheus-{proxy,agent}.rb` hold `@VERSION@` / `@SHA256@` placeholders that `make homebrew-formulae` fills in from the published GitHub release (step 9 of `docs/RELEASE.md`).

Leave historical mentions alone (`CHANGELOG.md`, `RELEASE_NOTES.md`, `docs/archive/`, the per-feature release annotations in `llms.txt`). The release flow itself is documented in `docs/RELEASE.md`.
