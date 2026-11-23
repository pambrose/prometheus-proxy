plugins {
  alias(libs.plugins.kotlin.jvm)
  // The repository's linter, which `build` runs (CI does), with the repository's .editorconfig
  alias(libs.plugins.pambrose.kotlinter)
  application
}

// The agent's Ktor plugin, which brings the agent: a release from Maven Central, the one -PproxyVersion names (the
// Docker build passes the latest release), or, with -PlocalProxy, this checkout, which settings.gradle.kts includes in
// its place.
val proxyVersion = providers.gradleProperty("proxyVersion").getOrElse("latest.release")

dependencies {
  implementation("com.pambrose:prometheus-proxy-ktor:$proxyVersion")
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.html.builder)
  implementation(libs.ktor.server.metrics.micrometer)
  implementation(libs.micrometer.registry.prometheus)
  implementation(libs.kotlin.logging)
  runtimeOnly(libs.logback.classic)
}

application {
  mainClass = "coffeeshop.MainKt"
}
