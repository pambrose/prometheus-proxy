# Both images are pinned by digest for reproducible builds (tags kept for readability); Dependabot bumps the digests
# to pick up security patches. To check one by hand: docker buildx imagetools inspect <image:tag>

# The runtime base: Google's distroless Debian 13 with glibc, CA certificates, and time zone data, and no shell or
# package manager. The Temurin JRE links only glibc (it bundles zlib and freetype), so these few Debian packages are
# all the OS the image carries, and all Trivy scans; distroless is rebuilt from Debian's updates. It publishes amd64,
# arm64, s390x, and ppc64le manifests, so the full multi-arch `make docker-push` platform set resolves.
FROM gcr.io/distroless/base-nossl-debian13:latest@sha256:d710a5c8bfe4cf7e726d7e1afd73e363a9fb02c2a11ad893d2d6185c1b9a89cb AS runtime

# The Java 25 (LTS) runtime comes from Temurin, which publishes the same four platforms; the fat JAR is Java 17
# bytecode, which runs unchanged on the newer JRE. The image takes only /opt/java/openjdk and the C.UTF-8 locale from
# this stage, which also does the steps that need a shell. Keep the -noble (Ubuntu 24.04) variant: its locale files
# load under Debian 13's glibc, which ContainersPackagingTest checks.
FROM eclipse-temurin:25-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce AS jre

# Add the user the image runs as, rather than running as root, to the base's passwd and group files, and make it an
# /app it owns. The UID and GID are fixed: the charts run the pods as 1001 (podSecurityContext in
# charts/*/values.yaml), so change them together.
COPY --from=runtime /etc/passwd /etc/group /rootfs/etc/
RUN echo 'prometheus:x:1001:1001::/app:/sbin/nologin' >> /rootfs/etc/passwd \
    && echo 'prometheus:x:1001:' >> /rootfs/etc/group \
    && mkdir /rootfs/app \
    && chown 1001:1001 /rootfs/app

FROM runtime
LABEL maintainer="Paul Ambrose <pambrose@mac.com>"

COPY --from=jre /rootfs/ /
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
# The base has no locales; without C.UTF-8, the JVM's native and stdout encodings fall back to ASCII.
COPY --from=jre /usr/lib/locale/C.utf8 /usr/lib/locale/C.utf8
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    LANG=C.UTF-8

# Run as that user, by number, so Kubernetes' runAsNonRoot can check it without a runAsUser.
USER 1001:1001

# Make /app the working directory
WORKDIR /app

COPY ./build/libs/prometheus-proxy.jar /app/prometheus-proxy.jar

# Default ports: scrape (8080), metrics (8082), admin (8092), dashboard (8094), and agent gRPC (50051).
EXPOSE 8080 8082 8092 8094 50051

CMD []

# G1 is the default GC on JDK 25 and MaxGCPauseMillis/UseStringDeduplication are non-experimental,
# so -XX:+UseG1GC and -XX:+UnlockExperimentalVMOptions were no-ops and have been removed.
# --enable-native-access=ALL-UNNAMED grants the classpath fat JAR native access (jansi's System.load),
# and --sun-misc-unsafe-memory-access=allow silences netty's terminally-deprecated sun.misc.Unsafe
# memory calls; both suppress JDK 25 startup warnings (drop the Unsafe flag once netty stops using it).
ENTRYPOINT ["java", "-server", "-XX:MaxGCPauseMillis=100", "-XX:+UseStringDeduplication", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-jar", "/app/prometheus-proxy.jar"]
