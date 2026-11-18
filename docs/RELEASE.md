# Release Creation

The version is defined once in `gradle.properties` (`version=…`) and read from there by Gradle, the
`Makefile` (`VERSION`), and the Docker image tags. `BuildConfig.APP_RELEASE_DATE` and
`BuildConfig.BUILD_TIME` are generated automatically on each build, so there is no release date to edit
by hand.

Steps 1-3 are edits to merge to `master`. After that, `make release` runs steps 4-6 and 8 from a clean, up-to-date
`master` checkout. It first runs `make release-check`, which publishes nothing and fails unless:

- the working tree is clean, on `master`, and matches `origin/master`;
- every GitHub check for that commit has passed, and each CI job `RELEASE_REQUIRED_JOBS` in the `Makefile` lists
  ran for it and passed;
- both changelogs have a dated section for the version (`## [VERSION] - YYYY-MM-DD` in `CHANGELOG.md`, and
  `_Released YYYY-MM-DD_` under `## VERSION` in `RELEASE_NOTES.md`);
- every file step 2 lists mentions the version, and none still has the previous release's version where step 2
  bumps it (an image tag, the Maven coordinate or `<version>`, a chart's `version` or `appVersion`, a `git clone`'s
  `--branch`, or the backticked examples);
- the version isn't a pre-release, its tag (if one exists) points at this commit, and it isn't already on Maven
  Central or Docker Hub;
- the GPG setup and Maven Central credentials `publish-maven-central` needs are in place (step 6), and Docker is
  running.

It then runs `make all-tests` and `make distro`, asks once, publishes to Maven Central and Docker Hub, checks the
published images with `make docker-verify`, tags the commit as the version and pushes the tag with `make release-tag`,
and deploys the docs site with `make gh-docs`; the tag comes first because the site's `git clone --branch` commands
check it out. It ends by printing the GitHub release (step 7) and Homebrew (step 9) steps, which stay manual. Run
`make release-check` on its own to see what's still missing. If a release stops after publishing somewhere, finish it
with the remaining targets, since `release-check` refuses a version that is already published.

Merging doesn't deploy the site; only a release does. The docs workflow (`.github/workflows/docs.yml`) builds the
site and the KDocs on every push to `master`, as a check `make release-check` requires, but deploys only from a
`workflow_dispatch`, which `make release` starts with `make gh-docs` once it has published. So the live site describes
the last release, which its visitors can install, until the next one: docs changes merged in between, steps 1-3
included, wait for it. To deploy a fix the live site can't wait for, run `make gh-docs`, which deploys `master` as it
is, unreleased pages included.

1) Bump `version` in `gradle.properties` (the single source of truth).

2) Update the `4.5.0` literals to the new version everywhere they are hard-coded
   (`git grep -n` for the outgoing version finds them all):
   - `README.md` — the Docker tag examples and the Maven Central dependency block. Also add a summary
     paragraph for the new version at the top of the **New Features** section; the earlier releases'
     paragraphs stay as they are.
   - `llms.txt` — the Docker tag examples and the Maven Central dependency block.
   - `etc/compose/proxy.yml` — the proxy and agent image tags.
   - `charts/prometheus-proxy/Chart.yaml` and `charts/prometheus-agent/Chart.yaml` — `version` and `appVersion`
     (the image tag the charts deploy by default).
   - `website/prometheus-proxy/docs/getting-started.md`, `index.md`, and `docker.md` — the Docker pull
     and run examples, plus the explicit-version-tag tip in `docker.md`.
   - `src/test/kotlin/website/DockerExamples.txt`, `EmbeddedAgentExamples.txt` (Gradle and Maven), and
     `KubernetesExamples.txt` — snippet sources included by the website's Docker, Embedded Agent, and
     Kubernetes pages, so the published site shows whatever version is here.
   - The `git clone --branch` tag the demos and the Helm install check out, so a reader runs the scripts released with
     the images they pull: `README.md` and `llms.txt`, `demos/basics/README.md`, `demos/network/README.md`, and
     `demos/kubernetes/README.md`, and the website's `basics-demo.md`, `network-demo.md`, `kubernetes-demo.md`, and
     `getting-started.md`.
   - This step, and the matching line in `.claude/skills/publishing-release/SKILL.md`.

   `RELEASE_VERSION_FILES` in the `Makefile` lists these files for `make release-check`; keep it in step with this
   list. `make site-version` prints the version the website's pages and snippets now give, and fails if they give
   more than one.

   Leave historical and illustrative mentions alone: `CHANGELOG.md`, `RELEASE_NOTES.md`,
   `docs/archive/`, the per-feature release annotations in `llms.txt`, the release reference in
   `.claude/rules/dashboard-constraints.md`, the `-PoverrideVersion` snapshot example in `AGENTS.md`, and
   the tag and title examples in step 7. `docs/PRODUCT.md` names the latest GitHub release as a product
   signal; refresh it if it should stay current.

3) Update `CHANGELOG.md` and `RELEASE_NOTES.md` with the changes in this release, renaming each file's
   `Unreleased` section to the new version and release date.

4) Verify everything passes before merging: `make tests` (or `make all-tests` to also run the
   Docker-based container suite).

5) Build the standalone fat jars: `make distro`. This produces `build/libs/prometheus-agent.jar` and
   `build/libs/prometheus-proxy.jar`.

6) Publish the release artifact to Maven Central: `make publish-maven-central`
   (runs `./gradlew publishAndReleaseToMavenCentral`). To publish a snapshot instead, use
   `make publish-snapshot`, which needs the same setup. Each target's prerequisites, which `make release-check`
   runs too, check that setup first and name what's missing:
   - `_check-gpg-env`, the signing key: `GPG_SIGNING_KEY_ID` is set, GPG holds its secret key, and the keychain
     entry `gradle-signing-password` (account `gpg-signing`) holds its passphrase.
   - `_check-maven-central-env`, the Maven Central credentials: the keychain entries `maven-central-username` and
     `maven-central-password` (account `maven-central`) hold a user token's name and password from
     central.sonatype.com. The targets pass them to Gradle in the publishing command's environment only, so keep
     them out of `~/.gradle/gradle.properties`, which every Gradle build on the machine reads. Add each entry once
     with the command below, which asks for the value, so it stays out of the shell's history:

     ```bash
     security add-generic-password -a maven-central -s maven-central-username -U -w
     security add-generic-password -a maven-central -s maven-central-password -U -w
     ```

7) Create a release on GitHub (https://github.com/pambrose/prometheus-proxy/releases):
   - **Tag**: the version with no `v` prefix (e.g. `4.0.1`). `make release` has pushed it already, so create the
     release on it: `gh release create <version> --verify-tag …`, as `make release` prints.
   - **Title**: the version with a `v` prefix (e.g. `v4.0.1`).
   - **Description**: summarize the changes and include a full-changelog link
     (e.g. `**Full Changelog**: https://github.com/pambrose/prometheus-proxy/compare/<prev>...<new>`).
   - Attach `build/libs/prometheus-agent.jar`, `build/libs/prometheus-proxy.jar`, and
     `build/libs/prometheus-proxy-<version>-sources.jar`.

8) Build and push the multi-arch Docker images: `make docker-push`. This tags both `:latest` and
   `:<version>`; it refuses to push pre-release versions (`-SNAPSHOT`/`-rc`/`-beta`/`-alpha`) as `:latest`.
   Once `make docker-verify` passes, push the release tag with `make release-tag`, which the docs' clone commands
   check out, then deploy the docs site with `make gh-docs`, so it shows the new version; `make gh-status` lists the
   docs workflow's runs.

9) Update the Homebrew formulae once the GitHub release is published: `make homebrew-formulae`, then commit and
   push the tap. The target downloads the release's `prometheus-proxy.jar` and `prometheus-agent.jar`, hashes
   them, and writes `Formula/prometheus-proxy.rb` and `Formula/prometheus-agent.rb` into a clone of
   [pambrose/homebrew-tap](https://github.com/pambrose/homebrew-tap) at `../homebrew-tap` (set `HOMEBREW_TAP_DIR`
   to use another path). The formulae's sources are in `etc/homebrew/` in this repository; change them there, not
   in the tap. The push starts the tap's CI, which audits both formulae, installs and tests them on macOS and Linux,
   and scrapes a metrics endpoint through the installed proxy and agent; check that it passes at
   https://github.com/pambrose/homebrew-tap/actions.
