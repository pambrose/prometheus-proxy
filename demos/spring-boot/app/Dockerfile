# The Spring Boot demo's roastery (../README.md). docker/compose.yml builds it one of two ways, which APP_BUILD picks:
#
#   compiled (make up)        builds the app here with Maven, against the release of com.pambrose:prometheus-proxy that
#                             PROXY_VERSION names, from Maven Central, so the demo needs only Docker
#   prebuilt (make up-local)  copies target/roastery.jar, which demo.sh built on the host against this checkout
ARG APP_BUILD=compiled

FROM maven:3.9-eclipse-temurin-25-noble@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS compiled
ARG PROXY_VERSION
WORKDIR /src
COPY pom.xml ./
COPY src ./src
# The cache keeps Maven's downloads between builds, so a second make up builds in seconds.
RUN --mount=type=cache,target=/root/.m2,id=roastery-maven \
    mvn --batch-mode --quiet -Dprometheus-proxy.version="${PROXY_VERSION:?}" -DskipTests package \
    && mkdir /jar && cp target/roastery.jar /jar/

FROM scratch AS prebuilt
COPY target/roastery.jar /jar/

FROM ${APP_BUILD} AS jar

# Spring Boot's own layout for an image: the app's jar beside lib/, its dependencies' jars, which `demo.sh versions`
# lists, as it runs.
FROM eclipse-temurin:25-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce AS extracted
COPY --from=jar /jar/roastery.jar /tmp/
RUN java -Djarmode=tools -jar /tmp/roastery.jar extract --destination /app

FROM eclipse-temurin:25-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce
RUN useradd --system --uid 1001 --home-dir /app roastery
COPY --from=extracted /app /app
USER 1001
WORKDIR /app
# Spring Boot's jar starts with java -jar, which reads JDK_JAVA_OPTIONS: they let Netty, which the agent's gRPC runs
# on, load its native TLS library and use sun.misc.Unsafe on Java 24 and later, and size the heap from the
# container's memory.
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75 --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/roastery.jar"]
