---
name: publishing-release
description: Use when publishing prometheus-proxy to Maven Central, cutting a release, running a snapshot publish, or bumping the project version — covers the Maven Central coordinates, GPG prerequisites, and the version-bump checklist.
---

# Publishing prometheus-proxy

Published to Maven Central as `com.pambrose:prometheus-proxy`. No JitPack.

Repository declarations are centralized in `settings.gradle.kts` via `dependencyResolutionManagement(FAIL_ON_PROJECT_REPOS)` and resolve solely from Maven Central.

## Publish targets

Snapshot and Maven Central release Make targets (`publish-snapshot`, `publish-maven-central`) require GPG environment variables and a keychain password entry; `make _check-gpg-env` validates them up-front.

## Bumping the version

When bumping the version, update `version` in `gradle.properties` and every hard-coded `4.0.1` literal (`git grep -n` for the outgoing version):

- `README.md` and `llms.txt` — Docker tag examples + Maven Central dependency block (README also gets a new release-summary paragraph at the top of **New Features**)
- `etc/compose/proxy.yml` — proxy and agent image tags
- `website/prometheus-proxy/docs/{getting-started,index,docker}.md` — Docker pull/run examples
- `src/test/kotlin/website/{DockerExamples,EmbeddedAgentExamples,KubernetesExamples}.txt` — snippet sources the website's Docker, Embedded Agent, and Kubernetes pages include
- this line, and step 2 of `docs/RELEASE.md`

Leave historical mentions alone (`CHANGELOG.md`, `RELEASE_NOTES.md`, `docs/archive/`, the per-feature release annotations in `llms.txt`). The release flow itself is documented in `docs/RELEASE.md`.
