// The Ktor demo's coffee shop (../README.md): a Ktor app that embeds the prometheus-proxy agent, built on its own,
// outside the repository's build. It reads the repository's version catalog, so its Kotlin, Ktor, and Logback move with
// the project's. With -PlocalProxy it builds against this checkout's library in place of a release, as `make up-local`
// and CI do.
rootProject.name = "coffee-shop"

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

// The checkout's build, which takes the place of com.pambrose:prometheus-proxy and its Ktor plugin, whatever version
// build.gradle.kts asks for. Gradle matches the root project to the agent's coordinates by its name, but the plugin's
// project, :ktor-plugin, is named apart from its artifact, so it's matched here.
if (providers.gradleProperty("localProxy").isPresent) {
  includeBuild("../../..") {
    dependencySubstitution {
      substitute(module("com.pambrose:prometheus-proxy-ktor")).using(project(":ktor-plugin"))
    }
  }
}
