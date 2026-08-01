---
name: publishing-release
description: Use when publishing prometheus-proxy to Maven Central, cutting a release, running a snapshot publish, or bumping the project version — covers the Maven Central coordinates, GPG prerequisites, and the version-bump checklist.
---

# Publishing prometheus-proxy

Published to Maven Central as `com.pambrose:prometheus-proxy`. No JitPack.

Repository declarations are centralized in `settings.gradle.kts` via `dependencyResolutionManagement(FAIL_ON_PROJECT_REPOS)` and resolve solely from Maven Central.

## Publish targets

Snapshot and Maven Central release Make targets (`publish-snapshot`, `publish-maven-central`) require GPG environment variables and a keychain password entry; `make check-gpg-env` validates them up-front.

## Bumping the version

When bumping the version, update `version` in `gradle.properties` and the `4.0.1` literals in `README.md` and `llms.txt` (Docker tag examples + Maven Central dependency block). The release flow itself is documented in `docs/RELEASE.md`.
