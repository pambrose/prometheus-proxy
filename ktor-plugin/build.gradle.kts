import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

// The Ktor plugin: installed in a Ktor app, it runs an embedded agent with the app and serves the app's metrics registry
// in-process. Published alongside the agent, at the agent's version. The root project applies these plugins, with their
// versions, so they're applied here by id.
plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.vanniktech.maven.publish")
  id("org.jetbrains.dokka")
  id("org.jetbrains.kotlinx.kover")
  id("dev.detekt")
  id("com.pambrose.kotlinter")
}

// As the root project takes it, for a snapshot or the consumer check's throwaway version
providers.gradleProperty("overrideVersion").orNull?.let { version = it }

val pluginArtifactId = "prometheus-proxy-ktor"

base.archivesName = pluginArtifactId

kotlin {
  jvmToolchain(libs.versions.jvm.get().toInt())
}

dependencies {
  // In the POM, at compile scope, since the plugin's API names their types: the agent, at this version (its
  // EmbeddedAgentConfig.Builder and EmbeddedAgentInfo); Ktor's server core (Application, ApplicationPlugin); and the
  // exposition formats the registry is written with, whose model holds the PrometheusRegistry the plugin takes.
  api(project(":"))
  api(libs.ktor.server.core)
  api(libs.prometheus.metrics.exposition.textformats)
  implementation(libs.kotlin.logging)

  testImplementation(libs.kotest)
  testImplementation(libs.mockk)
  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.ktor.server.config.yaml)
  testImplementation(libs.ktor.server.metrics.micrometer)
  testImplementation(libs.micrometer.registry.prometheus)
  testImplementation(libs.prometheus.metrics.core)
  testRuntimeOnly(libs.logback.classic)
}

tasks.test {
  useJUnitPlatform()
  // `./gradlew test --tests "io.prometheus.containers.*"`, as make container-tests, lincheck-tests, and the like run,
  // filters this task too; a filter that names none of its specs runs none, rather than failing the build
  filter.isFailOnNoMatchingTests = false
  // On the runtime the root project's tests and the Docker images use, as the main code compiles for Java 17
  javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(libs.versions.testJvm.get()) }
}

detekt {
  buildUponDefaultConfig = true
  allRules = false
  config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

kotlinter {
  ignoreFormatFailures = false
  ignoreLintFailures = false
  reporters = arrayOf("checkstyle", "plain")
}

// The root project aggregates this project's coverage (kover(project(":ktor-plugin"))), so its floors cover the plugin
// too; its per-class rule reads a variant of the same name here.
kover {
  currentProject {
    createVariant("perClass") {
      add("jvm")
    }
  }
}

dokka {
  moduleName.set(pluginArtifactId)
  dokkaSourceSets.main {
    sourceLink {
      localDirectory.set(file("src/main/kotlin"))
      remoteUrl("https://github.com/pambrose/prometheus-proxy/tree/master/ktor-plugin/src/main/kotlin")
      remoteLineSuffix.set("#L")
    }
  }
}

mavenPublishing {
  configure(
    KotlinJvm(
      javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
      sourcesJar = SourcesJar.Sources(),
    ),
  )
  coordinates(artifactId = pluginArtifactId)

  pom {
    name.set(pluginArtifactId)
    description.set("A Ktor plugin that runs a prometheus-proxy agent with the app and serves its metrics in-process.")
    url.set("https://github.com/pambrose/prometheus-proxy")
    licenses {
      license {
        name.set("Apache License 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0")
      }
    }
    developers {
      developer {
        id.set("pambrose")
        name.set("Paul Ambrose")
        email.set("paul@pambrose.com")
      }
    }
    scm {
      connection.set("scm:git:git://github.com/pambrose/prometheus-proxy.git")
      developerConnection.set("scm:git:ssh://github.com/pambrose/prometheus-proxy.git")
      url.set("https://github.com/pambrose/prometheus-proxy")
    }
  }

  publishToMavenCentral(automaticRelease = true)
  // As the root project does: unsigned when no GPG key is given, as for a local publish
  if (project.findProperty("signingInMemoryKey") != null) {
    signAllPublications()
  }
}
