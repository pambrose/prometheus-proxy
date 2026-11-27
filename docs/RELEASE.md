# Release Creation

The version is defined once in `gradle.properties` (`version=…`) and read from there by Gradle, the
`Makefile` (`VERSION`), and the Docker image tags. `BuildConfig.APP_RELEASE_DATE` and
`BuildConfig.BUILD_TIME` are generated from the time of the commit the build is from, so there is no release
date to edit by hand.

Steps 1-3 are edits to merge to `master`. After that, `make release` runs steps 4-8 from a clean, up-to-date `master`
checkout. It first runs `make release-check`, which publishes nothing and fails unless:

- the working tree is clean, on `master`, and matches `origin/master`;
- every GitHub check for that commit has passed, and each CI job `RELEASE_REQUIRED_JOBS` in the `Makefile` lists
  ran for it and passed;
- both changelogs have a section for the version dated the day it runs (`## [VERSION] - YYYY-MM-DD` in
  `CHANGELOG.md`, and `_Released YYYY-MM-DD_` under `## VERSION` in `RELEASE_NOTES.md`), which the last change merged
  before `make release` sets (step 3);
- every file step 2 lists mentions the version, and none still has the previous release's version where step 2
  bumps it (an image tag, the Maven coordinate or `<version>`, the Spring Boot demo's `<prometheus-proxy.version>`, a
  chart's `version` or `appVersion`, a `git clone`'s `--branch`, the backticked examples, the tag in a
  `raw.githubusercontent.com` link, or a GitHub release's page or download link, as the Quick Start's JAR tab has);
- every demo's `NEEDS_RELEASE_AFTER` is below the version, so `make up` from the release's tag pulls the release's
  images instead of building the proxy and agent from source (step 2);
- the version isn't a pre-release, its tag (if one exists) points at this commit, and none of the agent, the Spring
  Boot starter, and the Ktor plugin is already on Maven Central at it, nor the images on Docker Hub;
- the GPG setup and Maven Central credentials `publish-maven-central` needs are in place (step 6), and Docker is
  running.

It then runs `make all-tests` (every test, Lincheck, the container suite, and `make tla-checks-full`, the full TLA+
checks, whose slower reconnect config CI runs only when `specs/tla/` changes) and `make distro`, asks once, publishes to
Maven Central and Docker Hub, checks the published images with `make docker-verify`, tags the commit as the version and
pushes the tag with `make release-tag`, creates the GitHub release on the tag with the JARs (`make github-release`, step
7), and deploys the docs site with `make gh-docs`. The tag and the GitHub release come first because the site's
`git clone --branch` commands check out the tag, and its Quick Start downloads the release's JARs. It ends by printing
the Homebrew step (step 9), which stays manual. Run `make release-check` on its own to see what's still missing. If a
release stops after publishing somewhere, finish it with the remaining targets, since `release-check` refuses a version
that is already published. `master` needs no change meanwhile: the demos build the proxy and agent from the checkout
until the release's images are on Docker Hub (step 2).

Merging doesn't deploy the site; only a release does. The docs workflow (`.github/workflows/docs.yml`) builds the
site and the KDocs on every push to `master`, as a check `make release-check` requires, but deploys only from a
`workflow_dispatch`, which `make release` starts with `make gh-docs` once it has published. So the live site describes
the last release, which its visitors can install, until the next one: docs changes merged in between, steps 1-3
included, wait for it. To deploy a fix the live site can't wait for, run `make gh-docs`, which deploys `master` as it
is, unreleased pages included.

1) Bump `version` in `gradle.properties` (the single source of truth).

2) Update the `4.7.1` literals to the new version everywhere they are hard-coded
   (`git grep -n` for the outgoing version finds them all):
   - `README.md` — the Docker tag examples and the Maven Central dependency block. Also replace the
     previous release's summary paragraph in the **New Features** section with one for the new version;
     the earlier releases' summaries live in `RELEASE_NOTES.md`, which the section links.
   - `llms.txt` — the Docker tag examples and the Maven Central dependency block.
   - `etc/compose/proxy.yml` — the proxy and agent image tags.
   - `examples/cloud-proxy/docker-compose.yml` — the proxy image tag.
   - `charts/prometheus-proxy/Chart.yaml` and `charts/prometheus-agent/Chart.yaml` — `version` and `appVersion`
     (the image tag the charts deploy by default).
   - `website/prometheus-proxy/docs/getting-started.md` and `docker.md` — the Docker pull and run examples, plus
     the explicit-version-tag tip in `docker.md`, and the Quick Start's JAR tab, whose release page and download links
     (`releases/tag/…` and `releases/download/…/`) name the release.
   - The release's tag in the `raw.githubusercontent.com` links, so a reader runs the released images and JARs with
     the configs and dashboards released with them: `README.md`, `llms.txt`, the website's `grafana.md`, and the
     snippet sources `DockerExamples.txt`, `GettingStarted.txt`, and `ConfigExamples.txt`.
   - `src/test/kotlin/website/DockerExamples.txt`, `EmbeddedAgentExamples.txt` (Gradle and Maven), and
     `KubernetesExamples.txt` — snippet sources included by the website's Docker, Embedded Agent, and
     Kubernetes pages, so the published site shows whatever version is here.
   - The `git clone --branch` tag the demos and the Helm install check out, so a reader runs the scripts released with
     the images they pull: `README.md` and `llms.txt`, `demos/basics/README.md`, `demos/network/README.md`,
     `demos/nginx/README.md`, `demos/kubernetes/README.md`, `demos/ktor/README.md`,
     `demos/spring-boot/README.md`, and `demos/plain-kotlin/README.md`, and the website's `basics-demo.md`,
     `network-demo.md`, `nginx-demo.md`, `kubernetes-demo.md`, `ktor-demo.md`, `spring-boot-demo.md`,
     `plain-kotlin-demo.md`, and `getting-started.md`.
   - `demos/spring-boot/app/pom.xml` — `prometheus-proxy.version`, the release of the Spring Boot starter the Spring
     Boot demo's roastery builds against with `make up`.
   - This step, and the matching line in `.claude/skills/publishing-release/SKILL.md`.

   `RELEASE_VERSION_FILES` in the `Makefile` lists these files for `make release-check`; keep it in step with this
   list. Once both changelogs have the new version's section, `make _check-version-files`, which `make release-check`
   runs, lists any of these files that doesn't name the version yet, and any line still at the previous release.
   `make site-version` prints the version the website's pages, its snippets, and `examples/` now give, and fails if
   they give more than one.

   Leave historical and illustrative mentions alone: `CHANGELOG.md`, `RELEASE_NOTES.md`,
   `docs/archive/`, the per-feature release annotations in `llms.txt`, the release reference in
   `.claude/rules/dashboard-constraints.md`, the `-PoverrideVersion` snapshot example in `AGENTS.md`, and
   the tag and title examples in step 7. `docs/PRODUCT.md` names the latest GitHub release as a product
   signal; refresh it if it should stay current.

   Leave each demo's `NEEDS_RELEASE_AFTER`, in `demos/*/demo.sh`, alone too: it isn't the version the docs give, but
   the newest release that lacks what the demo shows. A demo's `make up` runs the release the docs give once that
   release is after `NEEDS_RELEASE_AFTER` and its images are on Docker Hub, and builds the proxy and agent from the
   checkout until then. So from the bump until `make release` pushes the images, however long a release that stops
   partway takes to finish, `make up` on `master` builds from the checkout, and once they're pushed, it pulls them,
   with no change on release day. When a demo comes to use something no release has yet, set its value to the latest
   release on Docker Hub; `make release-check` refuses a release while any demo's value isn't below the version, since
   `make up` from the release's tag would then build from source.

3) Update `CHANGELOG.md` and `RELEASE_NOTES.md` with the changes in this release, renaming each file's
   `Unreleased` section to the new version. Open `RELEASE_NOTES.md`'s section with a paragraph that sums up the
   release: `make release` gives the section to the GitHub release as its notes (step 7). Date both sections with the
   release day (`## [VERSION] - YYYY-MM-DD` and `_Released YYYY-MM-DD_`) in the last change merged before
   `make release`: `make release-check` refuses a date other than the day it runs, so a release cut later than planned
   dates them again first.

4) Verify everything passes before merging: `make tests` (or `make all-tests` to also run Lincheck, the
   Docker-based container suite, and the full TLA+ checks).

5) Build the standalone fat jars: `make distro`. This produces `build/libs/prometheus-agent.jar` and
   `build/libs/prometheus-proxy.jar`.

6) Publish the release artifacts to Maven Central, the agent (`com.pambrose:prometheus-proxy`), the Spring Boot
   starter (`com.pambrose:prometheus-proxy-spring-boot-starter`, from `spring-boot-starter/`), and the Ktor plugin
   (`com.pambrose:prometheus-proxy-ktor`, from `ktor-plugin/`), at the same version: `make publish-maven-central` (runs
   `./gradlew publishAndReleaseToMavenCentral`, which publishes all three). To publish a
   snapshot instead, use
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

7) The release on GitHub (https://github.com/pambrose/prometheus-proxy/releases), which `make release` creates with
   `make github-release` once it has pushed the tag, before it deploys the docs site, so the Quick Start's JAR
   downloads work from the start:
   - **Tag**: the version with no `v` prefix (e.g. `4.0.1`), which `make release-tag` pushed.
   - **Title**: the version with a `v` prefix (e.g. `v4.0.1`).
   - **Description**: `RELEASE_NOTES.md`'s section for the version, its headings a level up and each paragraph and
     list item on one line, since GitHub shows a release's line breaks as written, then a full-changelog link
     (e.g. `**Full Changelog**: https://github.com/pambrose/prometheus-proxy/compare/<prev>...<new>`).
     `make github-release-notes` prints it. To correct a published release's notes, fix `RELEASE_NOTES.md`, then
     replace them with `make github-release-notes > notes.md` and `gh release edit <version> --notes-file notes.md`.
   - **Assets**: `build/libs/prometheus-agent.jar`, `build/libs/prometheus-proxy.jar`, and
     `build/libs/prometheus-proxy-<version>-sources.jar`, which `make distro` builds.

   A release already there, made by hand or by a run that stopped partway, keeps its description and gets the JARs
   again.

8) Build and push the multi-arch Docker images: `make docker-push`. This tags both `:latest` and
   `:<version>`; it refuses to push pre-release versions (`-SNAPSHOT`/`-rc`/`-beta`/`-alpha`) as `:latest`.
   Once `make docker-verify` passes, push the release tag with `make release-tag`, which the docs' clone commands
   check out, create the GitHub release on it with `make github-release` (step 7), then deploy the docs site with
   `make gh-docs`, so it shows the new version; `make gh-status` lists the docs workflow's runs.

9) Update the Homebrew formulae once the GitHub release is published: `make homebrew-formulae`, then commit and
   push the tap. The target downloads the release's `prometheus-proxy.jar` and `prometheus-agent.jar`, hashes
   them, and writes `Formula/prometheus-proxy.rb` and `Formula/prometheus-agent.rb` into a clone of
   [pambrose/homebrew-tap](https://github.com/pambrose/homebrew-tap) at `../homebrew-tap` (set `HOMEBREW_TAP_DIR`
   to use another path). The formulae's sources are in `etc/homebrew/` in this repository; change them there, not
   in the tap. The push starts the tap's CI, which audits both formulae, installs and tests them on macOS and Linux,
   and scrapes a metrics endpoint through the installed proxy and agent; check that it passes at
   https://github.com/pambrose/homebrew-tap/actions.
