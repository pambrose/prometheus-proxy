# Docker Host Setup for the Container Tests

The Testcontainers end-to-end suite (`io.prometheus.containers.*`, run via `make container-tests`,
`make scaling-tests`, and the `make scaling-*` presets) stands up many JVM containers at once. The
**heavy scaling presets need more RAM than a stock Docker Desktop install allocates**, so a new machine
must bump the Docker VM's memory before those targets will pass. This document explains why, what to
change, and how to verify it.

For everything else about the test suite, see [`TESTING.md`](TESTING.md).

## Why the default is not enough

Each proxy and agent container is a JVM. Testcontainers does **not** set a per-container memory limit by
default, so an uncapped JVM sizes its max heap to **25% of the Docker VM's RAM** (`-XX:MaxRAMPercentage=25`)
— about **2 GiB per JVM on a stock 8 GB Docker Desktop VM**.

The connection-stress presets fan out to dozens of JVMs at once. `make scaling-agents`, for example,
starts **~81 containers**: 40 agents + 40 nginx stubs + 1 proxy (**41 JVMs**). With uncapped 2 GiB heaps
that is ~80 GiB of potential heap on an 8 GB VM, so the VM is wildly over-committed and the Linux
OOM-killer inside the VM reaps the proxy mid-scrape. The symptom in the test output is:

```
the server prematurely closed the connection      # proxy under memory pressure
...
java.net.ConnectException: Connection refused      # proxy container was OOM-killed
```

`ContainersScalingTest` now caps each container's memory so the JVMs auto-size their heaps to the cgroup
limit instead of the host's RAM (see [How the test adapts](#how-the-test-adapts-to-the-vm-size) below), but
the cap is still divided across all the agents — so the **size of the Docker VM sets how comfortably the big
presets run**.

## Recommended Docker VM memory

| Workload | Make target(s) | Docker VM memory |
|----------|----------------|------------------|
| Default suite (1–2 agents per scenario) | `make container-tests`, `make all-tests` | **8 GB** (the Docker Desktop default) is fine |
| Connection / fan-out stress (40+ agents) | `make scaling-agents`, `make scaling-soak`, `make all-scaling` | **16 GB recommended** |
| Everything, comfortably | all of the above | **16 GB** |

CPUs and disk: the defaults are fine. The container suite is memory-bound, not CPU-bound; any modern
multi-core allocation works.

## Configuring a new machine

### Option A — Docker Desktop GUI (simplest)

1. Open **Docker Desktop → Settings (gear icon) → Resources**.
2. Set **Memory limit** to **16 GB** (slide the **Memory** slider).
3. Click **Apply & restart** and wait for Docker to come back up.

### Option B — settings file + restart (scriptable / headless)

Docker Desktop on macOS stores its settings in
`~/Library/Group Containers/group.com.docker/settings-store.json`. Add (or edit) the `MemoryMiB` key —
the value is in MiB, so 16 GiB = `16384`:

```jsonc
{
  // …existing keys…
  "MemoryMiB": 16384,
  // …existing keys…
}
```

> The current `settings-store.json` uses PascalCase keys (`AnalyticsEnabled`, `SettingsVersion`, …); the
> memory key matching that format is **`MemoryMiB`**. (Older Docker Desktop versions used a `settings.json`
> with a camelCase `memoryMiB` — if your install still uses that file, use the camelCase form there.)

Then restart Docker Desktop for the change to take effect (this stops any running containers):

```bash
osascript -e 'quit app "Docker"'   # or: killall Docker
open -a Docker
```

### Verify

After the GUI apply-and-restart or the file edit + restart, confirm the new allocation:

```bash
docker info --format '{{.MemTotal}}' | awk '{printf "%.2f GiB\n", $1/1073741824}'
# e.g. 15.60 GiB  (16384 MiB minus a small amount of VM overhead)
```

A 16384 MiB setting reports as ~15.6 GiB because the VM reserves a little for itself — that is expected.

## How the test adapts to the VM size

`ContainersScalingTest` reads the Docker VM's memory at runtime and gives each proxy/agent container an
explicit memory limit, so the cap **scales to whatever host runs it** — no per-machine code edits:

- The proxy gets a fixed, generous cap.
- The remaining ~70% of the VM is divided across the agents, clamped to a workable per-agent range.

So the same `make scaling-agents` run that gets ~160 MiB per agent on an 8 GB VM gets ~266 MiB per agent on
a 16 GB VM — enough headroom that a connected, actively-scraped agent does not GC-thrash. (If an agent is
squeezed too tight it can stall, drop its heartbeat stream, and have its paths evicted by the proxy; a
scrape of that path then returns `404`. More VM memory removes that risk.)

The logic lives in `ContainersScalingTest.kt` (`dockerVmMib`, `agentMemoryMib()`, `withMemoryMib()`). The
only thing the host must provide is enough total RAM; the test sizes the per-container caps from it.

## Prerequisites checklist for a new machine

- [ ] Docker Desktop (or a Docker engine) installed and running.
- [ ] Docker VM memory set to **16 GB** for the heavy scaling presets (see above); 8 GB is fine for the
      default `make container-tests`.
- [ ] `docker info` succeeds (the Makefile auto-detects `DOCKER_HOST` from the active Docker context).
- [ ] The fat JARs build (`make container-tests` / `make scaling-tests` build them first via the `jars` target).

Then:

```bash
make container-tests   # full end-to-end suite (8 GB OK)
make scaling-agents    # 40-agent connection-stress preset (16 GB recommended)
```
