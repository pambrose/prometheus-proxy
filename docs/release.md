# Release Creation 

1) Create branch

2) Bump version in source  

3) Modify code

4) Verify tests run cleanly: `make tests`

5) Build distro: `make distro`

6) Check in branch and merge 

7) Create release on github and upload target/distro/prometheus-proxy.jar and  target/distro/prometheus-agent.jar

8) Build and push docker images: `make docker-build docker-push`

9) Update the *prometheus-proxy* and *prometheus-agent* repository descriptions on 
the Docker hub with the latest version of *README.md*.