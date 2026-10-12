---
name: publishing-release
description: Use when publishing prometheus-proxy to Maven Central, cutting a release, running a snapshot publish, or bumping the project version — covers the Maven Central coordinates, GPG prerequisites, and the version-bump checklist.
---

# Publishing prometheus-proxy

Published to Maven Central as `com.pambrose:prometheus-proxy`. No JitPack.

Repository declarations are centralized in `settings.gradle.kts` via `dependencyResolutionManagement(FAIL_ON_PROJECT_REPOS)` and resolve solely from Maven Central.

## Publish targets

Snapshot and Maven Central release Make targets (`publish-snapshot`, `publish-maven-central`) require GPG environment variables and a keychain password entry; `make _check-gpg-env` validates them up-front.

`make release` cuts a release once the version bump and changelogs are merged: it gates with `make release-check` (clean, up-to-date `master` whose GitHub CI passed; changelogs and bumped files name the version; not already on Maven Central or Docker Hub; GPG and Docker ready), runs `make all-tests` and `make distro`, asks once, then publishes to Maven Central and Docker Hub and runs `make docker-verify`. The GitHub release and Homebrew tap stay manual; it prints both. See `docs/RELEASE.md`.

## Bumping the version

When bumping the version, update `version` in `gradle.properties` and every hard-coded `4.3.0` literal (`git grep -n` for the outgoing version):

- `README.md` and `llms.txt` — Docker tag examples + Maven Central dependency block (README also gets a new release-summary paragraph at the top of **New Features**)
- `etc/compose/proxy.yml` — proxy and agent image tags
- `charts/prometheus-{proxy,agent}/Chart.yaml` — `version` and `appVersion` (the image tag the charts deploy)
- `website/prometheus-proxy/docs/{getting-started,index,docker}.md` — Docker pull/run examples
- `src/test/kotlin/website/{DockerExamples,EmbeddedAgentExamples,KubernetesExamples}.txt` — snippet sources the website's Docker, Embedded Agent, and Kubernetes pages include
- this line, and step 2 of `docs/RELEASE.md`

`RELEASE_VERSION_FILES` in the `Makefile` lists the same files for `make release-check`; keep it in step.

The Homebrew formulae need no edit for a bump: `etc/homebrew/prometheus-{proxy,agent}.rb` hold `@VERSION@` / `@SHA256@` placeholders that `make homebrew-formulae` fills in from the published GitHub release (step 9 of `docs/RELEASE.md`).

Leave historical mentions alone (`CHANGELOG.md`, `RELEASE_NOTES.md`, `docs/archive/`, the per-feature release annotations in `llms.txt`). The release flow itself is documented in `docs/RELEASE.md`.
