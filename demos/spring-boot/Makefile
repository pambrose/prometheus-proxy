.PHONY: default help up up-local down status open tour logs

# The Spring Boot demo's own targets (README.md), so the demo is self-contained; the root Makefile has none. Run them
# from this directory (`make up`), with `make -C demos/spring-boot up`, or with `make -f demos/spring-boot/Makefile up`:
# every path below comes from this Makefile's own location, not the directory make runs in. GNU Make 3.81, macOS's
# make, runs it too.

# This directory. Taken before any include, while this file is the last in MAKEFILE_LIST.
DEMO_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))

# demo.sh runs from this directory as ./demo.sh, so the commands it prints with $0 read `./demo.sh peek`, as the
# README's do. The settings the scripts read, the SPRING_BOOT_DEMO_*_PORT variables, reach them both from the
# environment (`SPRING_BOOT_DEMO_APP_PORT=8491 make up`) and from make's command line
# (`make up SPRING_BOOT_DEMO_APP_PORT=8491`), since make exports a command-line variable to its recipes.
DEMO = cd "$(DEMO_DIR)" && ./demo.sh

# demo.sh runs the root Makefile's site-version target with the make that ran this one, so `gmake up` works where GNU
# make is installed as gmake, with no make. Exported, rather than passed as $(MAKE) in a recipe, which would make
# `make -n up` start the demo.
export MAKE

default: help

help:  ## Show this help (list of targets)
	@awk 'BEGIN {FS = ":.*?## "; printf "Usage: make <target>\n\nTargets:\n"} \
		/^[a-zA-Z0-9_-]+:.*?## / {printf "  \033[36m%-24s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# up runs the proxy image of the latest release, from Docker Hub, and builds the roastery against that release's
# library, from Maven Central, unless that release predates what the demo shows; then it builds all three, as up-local
# always does, from this checkout, so the demo shows the code as it is.
up:  ## Start the demo with the latest release, and print its URLs (needs Docker)
	@$(DEMO) up

up-local:  ## The same, with the library, proxy, and roastery built from this checkout (needs a JDK 17 or later and Maven too)
	@$(DEMO) up-local

down:  ## Stop the demo and remove its containers and network
	@$(DEMO) down

status:  ## Show the containers, the roastery's state, and the URLs
	@$(DEMO) status

open:  ## Open the roastery, the proxy's and the agent's dashboards, and Prometheus in the browser
	@$(DEMO) open

tour:  ## Walk through the demo's guided tour, pausing before each step (PART=n starts from part n)
	@$(DEMO) tour $(PART)

logs:  ## Follow the logs (SERVICE=name for one service: roastery, proxy, prometheus)
	@$(DEMO) logs $(SERVICE)
