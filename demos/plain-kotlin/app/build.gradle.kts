plugins {
  alias(libs.plugins.kotlin.jvm)
  // The repository's linter, which `build` runs (CI does), with the repository's .editorconfig
  alias(libs.plugins.pambrose.kotlinter)
  application
}

// The agent: a release from Maven Central, the one -PproxyVersion names (the Docker build passes the latest release),
// or, with -PlocalProxy, this checkout, which settings.gradle.kts includes in its place. It brings the Prometheus Java
// client's registry the oven's metrics are in, and the agent serves it in-process.
val proxyVersion = providers.gradleProperty("proxyVersion").getOrElse("latest.release")

dependencies {
  implementation("com.pambrose:prometheus-proxy:$proxyVersion")
  implementation(libs.prometheus.metrics.core)
  implementation(libs.jedis)
  implementation(libs.kotlin.logging)
  runtimeOnly(libs.logback.classic)
}

application {
  mainClass = "bakery.MainKt"
}
