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
# Checks what a Maven build that embeds the agent gets from the published POM, with the Maven project beside this
# script. `make consumer-check` publishes this checkout to the local Maven repository at a throwaway version, then runs
# this with that version. For a project that depends only on com.pambrose:prometheus-proxy, it checks that:
#
#   - Jackson, every netty-tcnative artifact (the per-platform natives included), and Netty resolve at the versions
#     gradle/libs.versions.toml pins, where Maven's own resolution picked older ones (November 2026 review, #21);
#   - no jar on the classpath carries a Logback config, which would replace the host application's own (#19), and the
#     library jar still carries reference.conf;
#   - the docs site's embedded-agent examples compile (#20).
#
# Every check runs, and the ones that failed are listed at the end. The reports Maven writes, the resolved
# dependencies and the classpath, are left in build/consumer-check.

set -euo pipefail

version="${1:?usage: check.sh <the prometheus-proxy version published to the local Maven repository>}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
catalog="$root/gradle/libs.versions.toml"
out="$root/build/consumer-check"

catalog_version() {
  local v
  v="$(sed -n "s/^$1 = \"\(.*\)\"/\1/p" "$catalog")"
  [ -n "$v" ] || { echo "ERROR: no $1 version in $catalog" >&2; exit 1; }
  echo "$v"
}

jackson="$(catalog_version jackson)"
jackson_annotations="$(catalog_version jacksonAnnotations)"
tcnative="$(catalog_version tcnative)"
netty="$(catalog_version netty)"
kotlin="$(catalog_version kotlin)"

mvn=(mvn -B -q -f "$root/etc/consumer-check/pom.xml" "-Dprometheus-proxy.version=$version" "-Dkotlin.version=$kotlin")

failed=()
fail() {
  echo "FAIL: $1" >&2
  failed+=("$1")
}

# Joins lines with spaces, for a one-line failure message, and indents them, for a listing.
joined() { paste -s -d ' ' -; }
indented() { sed 's/^/    /'; }

mkdir -p "$out"
deps="$out/dependencies.txt"
classpath="$out/classpath.txt"
rm -f "$deps" "$classpath"

echo "==> Resolving com.pambrose:prometheus-proxy:$version as a Maven consumer"
"${mvn[@]}" dependency:list dependency:build-classpath -Dsort=true "-DoutputFile=$deps" "-Dmdep.outputFile=$classpath"

# dependency:list writes one group:artifact:type[:classifier]:version:scope per line, followed by a module name. This
# prints those whose group matches the first argument as group:artifact[:classifier]:version, so a native jar's line
# is the only one with four fields.
resolved() {
  awk -v group="$1" '
    NF && $1 ~ /:/ {
      n = split($1, f, ":")
      if (f[1] ~ group) print f[1] ":" f[2] (n == 6 ? ":" f[4] : "") ":" f[n - 1]
    }' "$deps"
}

# The lines read whose artifact matches the first argument and whose version isn't the second.
not_at() { awk -F: -v artifact="$1" -v want="$2" '$2 ~ artifact && $NF != want'; }

echo "==> Jackson: $jackson, and jackson-annotations $jackson_annotations"
jackson_resolved="$(resolved '^com[.]fasterxml[.]jackson')"
indented <<< "$jackson_resolved"
for module in jackson-core jackson-databind jackson-annotations; do
  echo "$jackson_resolved" | awk -F: -v m="$module" '$2 == m { found = 1 } END { exit !found }' \
    || fail "Jackson: $module isn't resolved"
done
bad="$(echo "$jackson_resolved" | awk -F: -v core="$jackson" -v ann="$jackson_annotations" \
  '$NF != ($2 == "jackson-annotations" ? ann : core)' | joined)"
[ -z "$bad" ] || fail "Jackson isn't at $jackson (jackson-annotations $jackson_annotations): $bad"

echo "==> netty-tcnative: $tcnative"
tcnative_resolved="$(resolved '^io[.]netty$' | awk -F: '$2 ~ /^netty-tcnative/')"
indented <<< "$tcnative_resolved"
natives="$(echo "$tcnative_resolved" | awk -F: 'NF == 4' | wc -l | tr -d ' ')"
[ "$natives" -gt 0 ] || fail "netty-tcnative: no per-platform native jar is resolved"
bad="$(echo "$tcnative_resolved" | not_at '' "$tcnative" | joined)"
[ -z "$bad" ] || fail "netty-tcnative isn't at $tcnative on every platform: $bad"

echo "==> Netty: $netty"
bad="$(resolved '^io[.]netty$' | awk -F: '$2 !~ /^netty-tcnative/' | not_at '' "$netty" | joined)"
[ -z "$bad" ] || fail "Netty isn't at $netty: $bad"

echo "==> Logback: no config on the classpath"
library=""
while IFS= read -r jar; do
  [ -n "$jar" ] || continue
  case "$jar" in */prometheus-proxy-"$version".jar) library="$jar" ;; esac
  configs="$(unzip -Z1 "$jar" 2>/dev/null | awk '/^logback(-test)?[.]xml$/' | joined)"
  [ -z "$configs" ] || fail "$(basename "$jar") carries $configs, which replaces the host's logging config"
done < <(tr ':' '\n' < "$classpath")
if [ -z "$library" ]; then
  fail "prometheus-proxy-$version.jar isn't on the classpath"
elif ! unzip -Z1 "$library" | awk '$0 == "reference.conf" { found = 1 } END { exit !found }'; then
  fail "prometheus-proxy-$version.jar has no reference.conf"
fi

echo "==> Compiling the docs site's embedded-agent examples"
"${mvn[@]}" clean compile || fail "the embedded-agent examples don't compile against the published POM"

if [ "${#failed[@]}" -gt 0 ]; then
  echo >&2
  echo "ERROR: the consumer check failed:" >&2
  printf '  %s\n' "${failed[@]}" >&2
  exit 1
fi
echo "Consumer check passed for com.pambrose:prometheus-proxy:$version"
