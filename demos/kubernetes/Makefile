.PHONY: default help up up-local down status open tour logs screenshots

# The Kubernetes demo's own targets (README.md), so the demo is self-contained; the root Makefile has none. Run them
# from this directory (`make up`), with `make -C demos/kubernetes up`, or with `make -f demos/kubernetes/Makefile up`:
# every path below comes from this Makefile's own location, not the directory make runs in. GNU Make 3.81, macOS's
# make, runs it too.

# This directory, and the repository root two levels up. Taken before any include, while this file is the last in
# MAKEFILE_LIST.
DEMO_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
REPO_DIR := $(abspath $(DEMO_DIR)/../..)

# demo.sh runs from this directory as ./demo.sh, so the commands it prints with $0 read `./demo.sh restore-config`, as
# the README's do. The settings the scripts read, the KUBERNETES_DEMO_*_PORT variables and bin/screenshots.sh's
# WAIT_SECS and SKIP_UPTIME_WAIT, reach them both from the environment (`KUBERNETES_DEMO_PROMETHEUS_PORT=9291 make up`)
# and from make's command line (`make up KUBERNETES_DEMO_PROMETHEUS_PORT=9291`), since make exports a command-line
# variable to its recipes.
DEMO = cd "$(DEMO_DIR)" && ./demo.sh

# demo.sh runs the root Makefile's targets (site-version, and jars for up-local) with the make that ran this one, so
# `gmake up` works where GNU make is installed as gmake, with no make. Exported, rather than passed as $(MAKE) in a
# recipe, which would make `make -n up` start the demo.
export MAKE

default: help

help:  ## Show this help (list of targets)
	@awk 'BEGIN {FS = ":.*?## "; printf "Usage: make <target>\n\nTargets:\n"} \
		/^[a-zA-Z0-9_-]+:.*?## / {printf "  \033[36m%-24s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# up runs the proxy and agent images of the latest release, from Docker Hub, unless that release predates what the demo
# shows; then it builds them, as up-local always does, from this checkout's jars, which the root Makefile's jars target
# builds, so the demo shows the code as it is.
up:  ## Create both kind clusters and install the demo, with the proxy and agent released on Docker Hub, and print its URLs
	@$(DEMO) up

up-local:  ## The same, with the proxy and agent built from this checkout (needs a JDK 17 or later too)
	@$(DEMO) up-local

down:  ## Delete both kind clusters
	@$(DEMO) down

status:  ## Show both clusters' pods and Services, the Helm releases, and the URLs
	@$(DEMO) status

open:  ## Open the proxy dashboard and Prometheus's targets in the browser
	@$(DEMO) open

tour:  ## Walk through the demo's guided tour, pausing before each step (PART=n starts from part n)
	@$(DEMO) tour $(PART)

logs:  ## Follow a workload's logs (SERVICE=name: agent, the default, proxy, prometheus, targets-sync, orders, ...)
	@$(DEMO) logs $(SERVICE)

# Retakes the website's Kubernetes Demo screenshots from a demo built from this checkout, so they show the current
# dashboard (needs Docker, kind, Chrome, ImageMagick, cwebp). It's for maintaining the site, not for presenting, so it
# has no `## ` description and `make help` leaves it out.
screenshots: up-local
	@cd "$(DEMO_DIR)" && ./bin/screenshots.sh
