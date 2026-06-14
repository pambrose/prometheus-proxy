# Release Creation

The version is defined once in `gradle.properties` (`version=…`) and read from there by Gradle, the
`Makefile` (`VERSION`), and the Docker image tags. `BuildConfig.APP_RELEASE_DATE` and
`BuildConfig.BUILD_TIME` are generated automatically on each build, so there is no release date to edit
by hand.

1) Bump `version` in `gradle.properties` (the single source of truth).

2) Update the `3.2.1` literals in `README.md` and `llms.txt` (the Docker tag examples and the Maven
   Central dependency block) to the new version.

3) Update `CHANGELOG.md` and `RELEASE_NOTES.md` with the changes in this release.

4) Verify everything passes before merging: `make tests` (or `make all-tests` to also run the
   Docker-based container suite).

5) Build the standalone fat jars: `make distro`. This produces `build/libs/prometheus-agent.jar` and
   `build/libs/prometheus-proxy.jar`.

6) Publish the release artifact to Maven Central: `make publish-maven-central`
   (runs `./gradlew publishAndReleaseToMavenCentral`). The required GPG environment is validated
   automatically by the target's `_check-gpg-env` prerequisite — see the GPG variables and keychain
   entry it checks. To publish a snapshot instead, use `make publish-snapshot`.

7) Create a release on GitHub (https://github.com/pambrose/prometheus-proxy/releases):
   - **Tag**: the version with no `v` prefix (e.g. `3.2.1`).
   - **Title**: the version with a `v` prefix (e.g. `v3.2.1`).
   - **Description**: summarize the changes and include a full-changelog link
     (e.g. `**Full Changelog**: https://github.com/pambrose/prometheus-proxy/compare/<prev>...<new>`).
   - Attach `build/libs/prometheus-agent.jar` and `build/libs/prometheus-proxy.jar`.

8) Build and push the multi-arch Docker images: `make docker-push`. This tags both `:latest` and
   `:<version>`; it refuses to push pre-release versions (`-SNAPSHOT`/`-rc`/`-beta`/`-alpha`) as `:latest`.
