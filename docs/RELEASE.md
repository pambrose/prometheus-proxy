# Release Creation

The version is defined once in `gradle.properties` (`version=…`) and read from there by Gradle, the
`Makefile` (`VERSION`), and the Docker image tags. `BuildConfig.APP_RELEASE_DATE` and
`BuildConfig.BUILD_TIME` are generated automatically on each build, so there is no release date to edit
by hand.

1) Bump `version` in `gradle.properties` (the single source of truth).

2) Update the `4.0.1` literals to the new version everywhere they are hard-coded
   (`git grep -n` for the outgoing version finds them all):
   - `README.md` — the Docker tag examples and the Maven Central dependency block. Also add a summary
     paragraph for the new version at the top of the **New Features** section; the earlier releases'
     paragraphs stay as they are.
   - `llms.txt` — the Docker tag examples and the Maven Central dependency block.
   - `etc/compose/proxy.yml` — the proxy and agent image tags.
   - `website/prometheus-proxy/docs/getting-started.md`, `index.md`, and `docker.md` — the Docker pull
     and run examples, plus the explicit-version-tag tip in `docker.md`.
   - `src/test/kotlin/website/DockerExamples.txt`, `EmbeddedAgentExamples.txt` (Gradle and Maven), and
     `KubernetesExamples.txt` — snippet sources included by the website's Docker, Embedded Agent, and
     Kubernetes pages, so the published site shows whatever version is here.
   - This step, and the matching line in `.claude/skills/publishing-release/SKILL.md`.

   Leave historical and illustrative mentions alone: `CHANGELOG.md`, `RELEASE_NOTES.md`,
   `docs/CODE_REVIEW_*.md`, the per-feature release annotations in `llms.txt`, the release reference in
   `.claude/rules/dashboard-constraints.md`, the `-PoverrideVersion` snapshot example in `CLAUDE.md`, and
   the tag and title examples in step 7. `docs/PRODUCT.md` names the latest GitHub release as a product
   signal; refresh it if it should stay current.

3) Update `CHANGELOG.md` and `RELEASE_NOTES.md` with the changes in this release, renaming each file's
   `Unreleased` section to the new version and release date.

4) Verify everything passes before merging: `make tests` (or `make all-tests` to also run the
   Docker-based container suite).

5) Build the standalone fat jars: `make distro`. This produces `build/libs/prometheus-agent.jar` and
   `build/libs/prometheus-proxy.jar`.

6) Publish the release artifact to Maven Central: `make publish-maven-central`
   (runs `./gradlew publishAndReleaseToMavenCentral`). The required GPG environment is validated
   automatically by the target's `_check-gpg-env` prerequisite — see the GPG variables and keychain
   entry it checks. To publish a snapshot instead, use `make publish-snapshot`.

7) Create a release on GitHub (https://github.com/pambrose/prometheus-proxy/releases):
   - **Tag**: the version with no `v` prefix (e.g. `4.0.1`).
   - **Title**: the version with a `v` prefix (e.g. `v4.0.1`).
   - **Description**: summarize the changes and include a full-changelog link
     (e.g. `**Full Changelog**: https://github.com/pambrose/prometheus-proxy/compare/<prev>...<new>`).
   - Attach `build/libs/prometheus-agent.jar` and `build/libs/prometheus-proxy.jar`.

8) Build and push the multi-arch Docker images: `make docker-push`. This tags both `:latest` and
   `:<version>`; it refuses to push pre-release versions (`-SNAPSHOT`/`-rc`/`-beta`/`-alpha`) as `:latest`.
