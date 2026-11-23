pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention").version("1.0.0")
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
  }
}

rootProject.name = "prometheus-proxy"

// The Spring Boot starter, published as com.pambrose:prometheus-proxy-spring-boot-starter at the agent's version
include("spring-boot-starter")

// The Ktor plugin, published as com.pambrose:prometheus-proxy-ktor at the agent's version
include("ktor-plugin")
