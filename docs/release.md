# Release Creation

1) Create branch

2) Bump version in source

3) Modify code

4) Update the release date in `build.gradle`

5) Verify tests run cleanly before merge with: `make tests`

6) Check in branch and merge

7) Go back to master

8) Verify tests run cleanly after merge with: `make tests`

9) Build distro with: `make distro`

10) Create release on GitHub (https://github.com/pambrose/prometheus-proxy/releases)
    and upload the *build/libs/prometheus-proxy.jar* and  *build/libs/prometheus-agent.jar* files.

11) Build and push docker images with: `make docker-push`
