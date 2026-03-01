pluginManagement {
  resolutionStrategy {
    eachPlugin {
      if (requested.id.namespace == "com.pambrose") {
        useModule("com.github.pambrose:pambrose-gradle-plugins:${requested.version}")
      }
    }
  }

  repositories {
    maven("https://jitpack.io")
    gradlePluginPortal()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention").version("1.0.0")
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
  }
}

rootProject.name = "prometheus-proxy"
