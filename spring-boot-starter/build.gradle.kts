import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

// The Spring Boot starter: auto-configuration that runs an embedded agent with a Spring Boot app and serves the app's
// metrics registry in-process. It's in Java, so Spring Boot's configuration processor writes the metadata IDEs
// complete its properties from, and it's published alongside the agent, at the agent's version.
plugins {
  `java-library`
  id("com.vanniktech.maven.publish")
}

// As the root project takes it, for a snapshot or the consumer check's throwaway version
providers.gradleProperty("overrideVersion").orNull?.let { version = it }

val starterArtifactId = "prometheus-proxy-spring-boot-starter"

base.archivesName = starterArtifactId

java {
  toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.jvm.get()) }
}

dependencies {
  // In the POM: the agent, at this version; the exposition formats RegistryMetricsSource writes a registry with, whose
  // PrometheusRegistry its constructor takes; and Micrometer's Prometheus registry, which has Spring Boot put the app's
  // meters in a PrometheusRegistry bean. An app's Spring Boot manages the last two, as it does when the app names them.
  api(project(":"))
  api(libs.prometheus.metrics.exposition.textformats)
  implementation(libs.micrometer.registry.prometheus)

  // Not in the POM, nor is the BOM: the app's own Spring Boot brings these, at its version.
  compileOnly(platform(libs.spring.boot.dependencies))
  compileOnly("org.springframework.boot:spring-boot-autoconfigure")
  compileOnly("org.springframework.boot:spring-boot-health")
  compileOnly("org.slf4j:slf4j-api")
  // The annotations on spring-boot-health's Health, which javac reads, and warns of, -Werror failing the build
  compileOnly("com.fasterxml.jackson.core:jackson-annotations")
  annotationProcessor(platform(libs.spring.boot.dependencies))
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
  annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")

  // The tests run at Spring Boot's versions, those it picks for the agent's libraries included, which are older than
  // the agent's own. Enforced, as Spring Boot's Maven parent and its Gradle dependency management do for an app, not
  // only recommended, so the agent runs with them as it would in the app.
  testImplementation(enforcedPlatform(libs.spring.boot.dependencies))
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-starter-actuator")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
  options.compilerArgs.add("-parameters")
}

// The published code compiles without a warning. The tests read classes, such as ProxyOptions, whose annotations'
// libraries aren't on their classpath, which javac warns of. And it compiles in full each time: an incremental compile
// that leaves the properties class out hands it to the configuration processor compiled, without the Javadoc the
// metadata's descriptions come from, so they'd be gone from the jar (ConfigurationMetadataTest). Nor is its output
// taken from the build cache, which an incremental compile may have filled under the same inputs; it takes a second.
tasks.compileJava {
  options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
  options.isIncremental = false
  outputs.doNotCacheIf("an incremental compile's output may lack the configuration metadata's descriptions") { true }
}

// The properties' getters and setters go undocumented, as Spring Boot's do: their fields' Javadoc describes them
tasks.javadoc {
  (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

tasks.test {
  useJUnitPlatform()
  // `./gradlew test --tests "io.prometheus.containers.*"`, as make container-tests, lincheck-tests, and the like run,
  // filters this task too; a filter that names none of its tests runs none, rather than failing the build
  filter.isFailOnNoMatchingTests = false
  // On the runtime the root project's tests and the Docker images use, as the main code compiles for Java 17
  javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(libs.versions.testJvm.get()) }
  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

mavenPublishing {
  configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = SourcesJar.Sources()))
  coordinates(artifactId = starterArtifactId)

  pom {
    name.set(starterArtifactId)
    description.set(
      "Spring Boot auto-configuration that runs a prometheus-proxy agent with the app and serves its metrics in-process.",
    )
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
