# The Spring Boot demo's roastery (../README.md). docker/compose.yml builds it one of two ways, which APP_BUILD picks:
#
#   compiled (make up)        builds the app here with Maven, against the release of com.pambrose:prometheus-proxy that
#                             PROXY_VERSION names, from Maven Central, so the demo needs only Docker
#   prebuilt (make up-local)  copies target/roastery.jar, which demo.sh built on the host against this checkout
ARG APP_BUILD=compiled

FROM maven:3.9-eclipse-temurin-26-noble@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS compiled
ARG PROXY_VERSION
WORKDIR /src
COPY pom.xml ./
COPY src ./src
# The cache keeps Maven's downloads between builds, so a second make up builds in seconds.
RUN --mount=type=cache,target=/root/.m2,id=roastery-maven \
    mvn --batch-mode --quiet -Dprometheus-proxy.version="${PROXY_VERSION:?}" -Dmaven.test.skip=true package \
    && mkdir /jar && cp target/roastery.jar /jar/

FROM scratch AS prebuilt
COPY target/roastery.jar /jar/

FROM ${APP_BUILD} AS jar

# Spring Boot's own layout for an image, the app's jar beside lib/, its dependencies' jars, which `demo.sh versions`
# lists, in layers, so a rebuild rewrites only those that changed: the app, and a snapshot of the library. A release
# build has no snapshot, so its layer is made empty.
FROM eclipse-temurin:25-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce AS extracted
COPY --from=jar /jar/roastery.jar /tmp/
RUN java -Djarmode=tools -jar /tmp/roastery.jar extract --layers --destination /layers \
    && mkdir -p /layers/snapshot-dependencies

FROM eclipse-temurin:25-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce
RUN useradd --system --uid 1001 --home-dir /app roastery
COPY --from=extracted /layers/dependencies/ /app/
COPY --from=extracted /layers/snapshot-dependencies/ /app/
COPY --from=extracted /layers/application/ /app/
USER 1001
WORKDIR /app
# The flags the proxy and agent images run with, and a heap sized from the container's memory, in JDK_JAVA_OPTIONS,
# which java -jar reads.
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75 --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/roastery.jar"]
