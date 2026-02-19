pluginManagement {
  resolutionStrategy {
    eachPlugin {
      if (requested.id.namespace == "com.pambrose") {
        useModule("com.github.pambrose:gradle-plugins:${requested.version}")
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
  repositories {
    mavenCentral()
  }
}

rootProject.name = "prometheus-proxy"
