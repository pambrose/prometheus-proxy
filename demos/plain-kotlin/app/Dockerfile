# The plain Kotlin demo's oven (../README.md). docker/compose.yml builds it one of two ways, which APP_BUILD picks:
#
#   compiled (make up)        compiles the app here, against the release of com.pambrose:prometheus-proxy that
#                             PROXY_VERSION names, from Maven Central, so the demo needs only Docker
#   prebuilt (make up-local)  copies build/install/oven, which demo.sh built on the host against this checkout
#
# The repository's version catalog comes in as the named build context `catalog`, since this directory is the build
# context and the catalog is outside it.
ARG APP_BUILD=compiled

# The Gradle that matches the repository's wrapper most closely, on the JDK the runtime below ships.
FROM gradle:9.9.2-jdk25@sha256:a16370cdf4fab2bdec80810ba7f7d577fc2ce86eb1332b067031fcc19d947ed5 AS compiled
ARG PROXY_VERSION
WORKDIR /src
# catalog is the named build context above, not a stage, which hadolint can't see
# hadolint ignore=DL3022
COPY --from=catalog libs.versions.toml /catalog/libs.versions.toml
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
# The cache keeps Gradle's downloads between builds, so a second make up builds in seconds.
RUN --mount=type=cache,target=/home/gradle/.gradle,id=oven-gradle \
    gradle --no-daemon --quiet installDist -Pcatalog=/catalog/libs.versions.toml -PproxyVersion="${PROXY_VERSION:?}" \
    && mkdir /app && cp -R build/install/oven/. /app/

FROM scratch AS prebuilt
COPY build/install/oven /app

FROM ${APP_BUILD} AS app

# The JRE the proxy and agent images take theirs from (etc/docker), on Ubuntu, with a shell and cat, which
# `demo.sh sockets` reads the oven's sockets with, from /proc/net.
FROM eclipse-temurin:25-jre-noble@sha256:f3dd0838e69b881cc382237d5901e74ae3705b1056ad964b2e0e523b174fef99
RUN useradd --system --uid 1001 --home-dir /app oven
COPY --from=app /app /app
USER oven
# The flags the proxy and agent images run with, and a heap sized from the container's memory.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
# No EXPOSE: the oven listens on no port. Its agent connects out to the proxy, and the oven out to Redis.
ENTRYPOINT ["/app/bin/oven"]
