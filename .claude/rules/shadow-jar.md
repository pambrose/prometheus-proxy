---
paths:
  - "build.gradle.kts"
  - "src/shadow/**"
  - "gradle/libs.versions.toml"
---

# Shadow JAR Service-File Merging

ShadowJar's default `DuplicatesStrategy` (EXCLUDE) drops duplicate-named entries *before* merging transformers run, so `mergeServiceFiles()` silently loses entries when grpc-core and grpc-netty-shaded both ship a same-named `META-INF/services` file — leaving the fat JAR without a DNS resolver, so the gRPC client defaults to the `unix` scheme on any non-IP hostname. The `agentJar`/`proxyJar` tasks fix this with a `filesMatching()` block that sets `DuplicatesStrategy.INCLUDE` on `META-INF/services/**` and `META-INF/*.kotlin_module` only (everything else keeps first-wins EXCLUDE semantics), letting `ServiceFileTransformer` and `KotlinModuleMetadataTransformer` merge all copies.

As a belt-and-braces guard against future Shadow regressions, the tasks also include `src/shadow/resources/META-INF/services/`, which pins `io.grpc.NameResolverProvider` (DNS + UDS) and `io.grpc.LoadBalancerProvider` (PickFirst + HealthCheckingRoundRobin). The static files don't affect the published Maven jar (they're under `src/shadow/`, not `src/main/`). If gRPC versions change provider class names, update those files to match.
