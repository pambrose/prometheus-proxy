.PHONY: default help up up-local down status open tour logs

# The plain Kotlin demo's own targets (README.md), so the demo is self-contained; the root Makefile has none. Run them
# from this directory (`make up`), with `make -C demos/plain-kotlin up`, or with `make -f demos/plain-kotlin/Makefile
# up`: every path below comes from this Makefile's own location, not the directory make runs in. GNU Make 3.81, macOS's
# make, runs it too.

# This directory. Taken before any include, while this file is the last in MAKEFILE_LIST.
DEMO_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))

# demo.sh runs from this directory as ./demo.sh, so the commands it prints with $0 read `./demo.sh rush`, as the
# README's do. The PLAIN_KOTLIN_DEMO_*_PORT variables reach it both from the environment
# (`PLAIN_KOTLIN_DEMO_GRAFANA_PORT=3601 make up`) and from make's command line (`make up
# PLAIN_KOTLIN_DEMO_GRAFANA_PORT=3601`), since make exports a command-line variable to its recipes.
DEMO = cd "$(DEMO_DIR)" && ./demo.sh

# demo.sh runs the root Makefile's site-version target with the make that ran this one, so `gmake up` works where GNU
# make is installed as gmake, with no make. Exported, rather than passed as $(MAKE) in a recipe, which would make
# `make -n up` start the demo.
export MAKE

default: help

help:  ## Show this help (list of targets)
	@awk 'BEGIN {FS = ":.*?## "; printf "Usage: make <target>\n\nTargets:\n"} \
		/^[a-zA-Z0-9_-]+:.*?## / {printf "  \033[36m%-24s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# up runs the proxy image of the release the docs give, from Docker Hub, and builds the oven against that release's
# library, from Maven Central, unless that release predates what the demo shows or isn't out yet; then it builds both,
# as up-local always does, from this checkout, so the demo shows the code as it is.
up:  ## Start the demo with the latest release, and print its URLs (needs Docker)
	@$(DEMO) up

up-local:  ## The same, with the library, proxy, and oven built from this checkout (needs a JDK 17 or later too)
	@$(DEMO) up-local

down:  ## Stop the demo and remove its containers and network
	@$(DEMO) down

status:  ## Show the containers, the ovens and their paths, the queue, and the URLs
	@$(DEMO) status

open:  ## Open the proxy's screens page, which holds its dashboard, Grafana, and Prometheus, in the browser
	@$(DEMO) open

tour:  ## Walk through the demo's guided tour, pausing before each step (PART=n starts from part n)
	@$(DEMO) tour $(PART)

logs:  ## Follow the logs (SERVICE=name for one service: oven, redis, counter, proxy, prometheus, grafana)
	@$(DEMO) logs $(SERVICE)
