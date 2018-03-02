# Release Creation 

1) Create branch

2) Bump version in source  

3) Modify code

4) Update the release date in *package-info.java*

5) Verify tests run cleanly before merge with: `make tests`

6) Check in branch and merge 

7) Go back to master

8) Verify tests run cleanly after merge with: `make tests`

9) Build distro with: `make distro`

10) Create release on github (https://github.com/pambrose/prometheus-proxy/releases) and 
upload the *target/distro/prometheus-proxy.jar* and  *target/distro/prometheus-agent.jar* files.

11) Build and push docker images with: `make docker-build docker-push`

12) Update the *prometheus-proxy* and *prometheus-agent* repository descriptions 
on [Docker hub](https://hub.docker.com) with the latest version of *README.md*.