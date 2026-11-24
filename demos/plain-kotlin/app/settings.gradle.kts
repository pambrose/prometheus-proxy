// The plain Kotlin demo's oven (../README.md): a plain Kotlin program, with no framework or HTTP server, that embeds
// the prometheus-proxy agent, built on its own, outside the repository's build. It reads the repository's version
// catalog, so its Kotlin, Jedis, and Logback move with the project's. With -PlocalProxy it builds against this
// checkout's library in place of a release, as `make up-local` and CI do.
rootProject.name = "oven"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
  versionCatalogs {
    create("libs") {
      // The Docker build copies the catalog in and names it with -Pcatalog; otherwise it's the repository's own.
      from(files(providers.gradleProperty("catalog").getOrElse("../../../gradle/libs.versions.toml")))
    }
  }
}

// The checkout's build, which takes the place of com.pambrose:prometheus-proxy, whatever version build.gradle.kts asks
// for: Gradle matches the checkout's root project to the agent's coordinates by its name.
if (providers.gradleProperty("localProxy").isPresent) {
  includeBuild("../../..")
}
