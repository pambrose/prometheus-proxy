# Release Creation

1) Bump version in `build.gradle.kts`

2) Update the release date in `Makefile`

3) Verify tests run cleanly before merge with: `make tests`

4) Build distro with: `make distro`

5) Create a release on GitHub (https://github.com/pambrose/prometheus-proxy/releases)
    and upload the *build/libs/prometheus-proxy.jar* and *build/libs/prometheus-agent.jar* files.

6) Build and push docker images with: `make docker-push`
