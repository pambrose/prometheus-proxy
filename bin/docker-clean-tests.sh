#!/usr/bin/env bash
#
# Copyright © 2026 Paul Ambrose
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Reclaim the Docker disk the Testcontainers suite leaves behind.
#
# Every ImageFromDockerfile gets its own random localhost/testcontainers/<hash>:latest tag, reaped only by
# a JVM shutdown hook that never fires when a run is killed or OOMs. Layers are content-shared, so
# thousands of tags resolve to a few hundred images, but the tags accumulate and the store bloats. That
# bloat is not cosmetic: it is a plausible trigger for containerd content GC racing a build, which
# surfaces as "NotFound: content digest sha256:...: not found" on a later run.
#
# Dropping the tags reclaims nothing on its own -- containerd leaves the content dangling until a prune --
# so the tag removal and the prune below are one operation, not two choices.
#
# SAFETY. This script never runs `docker image prune -a`, `docker system prune`, or any unfiltered
# removal. Those delete every unused *tagged* image, which on a development box means the databases,
# language runtimes, and personal app images that have nothing to do with this repo. Instead:
#   - images are matched on the localhost/testcontainers/ prefix
#   - containers and networks are matched on Testcontainers' own org.testcontainers=true label
#   - the prune is dangling-only, which by definition cannot touch a tagged image
# So an unrelated image is not merely unlikely to be removed; it is unreachable from these selectors.

set -euo pipefail

readonly TC_IMAGE_PREFIX='localhost/testcontainers/'
readonly TC_LABEL='org.testcontainers=true'

# Third-party images the suite pulls. Mirrors ContainerTestSupport.kt plus Testcontainers' own reaper.
# Removing these is safe but costs a re-pull on the next run, hence --base rather than the default.
# Matched by repository, not tag, so a version bump cannot make this stale.
readonly BASE_IMAGE_PATTERN='^(nginx|prom/prometheus|testcontainers/ryuk):'

dry_run=false
assume_yes=false
do_cache=false
do_base=false
force=false

usage() {
  cat <<'EOF'
Usage: bin/docker-clean-tests.sh [options]

Reclaims the Docker disk left behind by the Testcontainers suite: the leaked
localhost/testcontainers/* image tags, stopped Testcontainers containers, leaked
Testcontainers networks, and the dangling-image prune that actually frees the bytes.

Options:
  -n, --dry-run   Show what would be removed and exit without changing anything.
  -y, --yes       Skip the confirmation prompt (required when not on a terminal).
      --cache     Also prune the Docker build cache. Often the largest single win;
                  costs a cold rebuild of the proxy/agent images next run.
      --base      Also remove the pulled base images (nginx, prom/prometheus,
                  testcontainers/ryuk). Costs a re-pull next run.
      --all       --cache and --base together.
      --force     Proceed even while a test run appears to be in flight. Only use
                  this if you know the run is dead; it will break a live one.
  -h, --help      Show this help.

Never removes untagged-but-in-use images, unrelated images, or anything lacking the
Testcontainers label. See the comment block at the top of this file for why.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    -n | --dry-run) dry_run=true ;;
    -y | --yes) assume_yes=true ;;
    --cache) do_cache=true ;;
    --base) do_base=true ;;
    --all)
      do_cache=true
      do_base=true
      ;;
    --force) force=true ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "Error: unknown option '$1'" >&2
      echo >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

command -v docker >/dev/null 2>&1 || {
  echo "Error: docker not found on PATH" >&2
  exit 1
}
docker info >/dev/null 2>&1 || {
  echo "Error: cannot reach the Docker daemon. Is Docker running?" >&2
  exit 1
}

# Newline-separated, possibly empty. macOS xargs has no -r, so callers must check for emptiness.
tc_image_tags() {
  docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep "^$TC_IMAGE_PREFIX" || true
}

stopped_tc_containers() {
  docker ps -a --filter "label=$TC_LABEL" --filter 'status=exited' --filter 'status=created' \
    --filter 'status=dead' --format '{{.ID}}' 2>/dev/null || true
}

running_tc_containers() {
  docker ps --filter "label=$TC_LABEL" --format '{{.Names}}\t{{.Image}}\t{{.Status}}' 2>/dev/null || true
}

tc_networks() {
  docker network ls --filter "label=$TC_LABEL" --format '{{.ID}}' 2>/dev/null || true
}

dangling_images() {
  docker images --filter 'dangling=true' --format '{{.ID}}' 2>/dev/null || true
}

base_image_tags() {
  docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep -E "$BASE_IMAGE_PATTERN" || true
}

count_of() {
  if [ -z "$1" ]; then
    echo 0
  else
    printf '%s\n' "$1" | wc -l | tr -d ' '
  fi
}

# Removes in batches: a single argv with thousands of tags can exceed the exec limit, and one docker call
# per item is unusably slow at the scale this leak reaches (5,800 tags observed). 500 per batch keeps argv
# near 25KB against a 1MB ARG_MAX. Callers must check for emptiness first -- macOS xargs has no -r.
remove_in_batches() {
  local items="$1"
  shift
  [ -z "$items" ] && return 0
  printf '%s\n' "$items" | xargs -n 500 "$@" >/dev/null 2>&1 || true
}

echo "=== Docker disk usage before ==="
docker system df
echo

running="$(running_tc_containers)"
if [ -n "$running" ]; then
  running_count="$(count_of "$running")"
  echo "A test run looks active: $running_count Testcontainers container(s) running." >&2
  # A scaling run holds dozens at once, so show a sample rather than pages of near-identical lines.
  printf '%s\n' "$running" | head -5 | sed 's/^/  /' >&2
  [ "$running_count" -gt 5 ] && echo "  ... and $((running_count - 5)) more" >&2
  echo >&2
  # A dry run changes nothing, so it still reports rather than refusing.
  if [ "$force" = false ] && [ "$dry_run" = false ]; then
    echo "Refusing to clean up: removing their images and networks would break the run." >&2
    echo "Wait for it to finish, or pass --dry-run to look without touching anything." >&2
    echo "Pass --force only if you know the run is already dead." >&2
    exit 1
  fi
  echo >&2
fi

images="$(tc_image_tags)"
containers="$(stopped_tc_containers)"
networks="$(tc_networks)"
dangling="$(dangling_images)"
bases=""
[ "$do_base" = true ] && bases="$(base_image_tags)"

echo "=== Planned removals ==="
printf '  %-34s %s\n' 'Testcontainers image tags' "$(count_of "$images")"
printf '  %-34s %s\n' 'Stopped Testcontainers containers' "$(count_of "$containers")"
printf '  %-34s %s\n' 'Testcontainers networks' "$(count_of "$networks")"
printf '  %-34s %s\n' 'Dangling images (prune)' "$(count_of "$dangling")"
[ "$do_base" = true ] && printf '  %-34s %s\n' 'Base images (--base)' "$(count_of "$bases")"
[ "$do_cache" = true ] && printf '  %-34s %s\n' 'Build cache (--cache)' 'all unused entries'
echo

if [ "$dry_run" = true ]; then
  echo "Dry run: nothing was removed."
  exit 0
fi

if [ -z "$images$containers$networks$dangling$bases" ] && [ "$do_cache" = false ]; then
  echo "Nothing to clean up."
  exit 0
fi

if [ "$assume_yes" = false ]; then
  if [ ! -t 0 ]; then
    echo "Error: not running on a terminal, so there is nobody to confirm with. Pass --yes." >&2
    exit 1
  fi
  printf 'Proceed? [y/N] '
  read -r reply
  case "$reply" in
    y | Y | yes | YES) ;;
    *)
      echo "Aborted."
      exit 0
      ;;
  esac
  echo
fi

# Containers first: a container still referencing an image blocks its removal.
if [ -n "$containers" ]; then
  echo "Removing stopped Testcontainers containers ..."
  remove_in_batches "$containers" docker rm -f
fi

if [ -n "$images" ]; then
  echo "Removing Testcontainers image tags ..."
  remove_in_batches "$images" docker rmi -f
fi

if [ -n "$bases" ]; then
  echo "Removing base images ..."
  remove_in_batches "$bases" docker rmi -f
fi

if [ -n "$networks" ]; then
  echo "Removing Testcontainers networks ..."
  # Batched: docker network rm removes every network it can and reports the rest as errors, so one still
  # attached does not prevent the others from going. A scaling run leaks these by the dozen.
  remove_in_batches "$networks" docker network rm
fi

# The step that actually reclaims the bytes. Dangling-only on purpose -- see the SAFETY note above.
echo "Pruning dangling images ..."
docker image prune -f >/dev/null

if [ "$do_cache" = true ]; then
  echo "Pruning build cache ..."
  docker builder prune -f >/dev/null
fi

echo
echo "=== Docker disk usage after ==="
docker system df
