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

// The Spring Boot whose dependency management the tests run under, which the tests' classpaths take from these, rather
// than from testImplementation, so the same tests can run under two
val springBootTestPlatform = configurations.dependencyScope("springBootTestPlatform")
val oldestSpringBootTestPlatform = configurations.dependencyScope("oldestSpringBootTestPlatform")
configurations.testCompileClasspath { extendsFrom(springBootTestPlatform.get()) }
configurations.testRuntimeClasspath { extendsFrom(springBootTestPlatform.get()) }

dependencies {
  // In the POM: the agent, at this version, which serves the app's PrometheusRegistry in-process, its API naming the
  // type; and Micrometer's Prometheus registry, which has Spring Boot put the app's meters in a PrometheusRegistry bean.
  // An app's Spring Boot manages the latter, as it does when the app names it.
  api(project(":"))
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
  // only recommended, so the agent runs with them as it would in the app. Twice: test runs them at the Spring Boot the
  // starter compiles against, and oldestSpringBootTest at the oldest it supports (below).
  springBootTestPlatform(enforcedPlatform(libs.spring.boot.dependencies))
  oldestSpringBootTestPlatform(enforcedPlatform(libs.spring.boot.oldest))
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-starter-actuator")
  // The same annotations on Health, which the tests read too, and compileOnly doesn't reach their compile
  testCompileOnly("com.fasterxml.jackson.core:jackson-annotations")
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

// The tests again, under the oldest Spring Boot the starter supports (spring-boot-oldest in libs.versions.toml), whose
// dependency management sets the agent's libraries furthest behind its own: the Spring Boot an app has may be any
// release from it on. The test classes are the ones test runs, on a classpath resolved as the tests' own is, for a JVM
// runtime at the toolchain's Java version, with that release's platform in place of the other.
//
// That Spring Boot sets two of the agent's libraries below the versions it can run on, and the starter page tells an
// app on it to set them, with Spring Boot's kotlin-coroutines.version and prometheus-client.version properties, to the
// agent's: kotlinx-coroutines at the version Ktor brings the agent, whose minimum RuntimeLibraries checks, and the
// Prometheus client at the catalog's. The run sets them as such an app does, so it fails when the agent needs a third.
val olderSpringBootVersions =
  mapOf(
    "org.jetbrains.kotlinx:kotlinx-coroutines" to "1.11.0",
    "io.prometheus:prometheus-metrics" to libs.versions.prometheus.get(),
  )

val oldestSpringBootTestRuntimeClasspath =
  configurations.resolvable("oldestSpringBootTestRuntimeClasspath") {
    extendsFrom(configurations.testImplementation.get(), configurations.testRuntimeOnly.get())
    extendsFrom(oldestSpringBootTestPlatform.get())
    val testRuntime = configurations.testRuntimeClasspath.get().attributes
    attributes { testRuntime.keySet().forEach { copyAttribute(testRuntime, it) } }
    resolutionStrategy.eachDependency {
      olderSpringBootVersions.forEach { (modules, version) ->
        if ("${requested.group}:${requested.name}".startsWith(modules)) useVersion(version)
      }
    }
  }

@Suppress("UNCHECKED_CAST")
fun AttributeContainer.copyAttribute(
  from: AttributeContainer,
  key: Attribute<*>,
) {
  val typed = key as Attribute<Any>
  attribute(typed, from.getAttribute(typed)!!)
}

val oldestSpringBootTest =
  tasks.register<Test>("oldestSpringBootTest") {
    description = "Runs the starter's tests under the oldest Spring Boot it supports."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().output + sourceSets.main.get().output + oldestSpringBootTestRuntimeClasspath.get()
    useJUnitPlatform()
    javaLauncher = tasks.test.flatMap { it.javaLauncher }
    testLogging {
      events("passed", "skipped", "failed")
      exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
  }

// test runs both, so build and check do, and a run that excludes test with -x test runs neither. A run that names
// tests with --tests, which filter test alone, leaves the other out; name it to run it.
val testsNamed =
  gradle.startParameter.taskRequests.any { request ->
    request.args.any { it == "--tests" || it.startsWith("--tests=") }
  }
if (!testsNamed) tasks.test { dependsOn(oldestSpringBootTest) }

mavenPublishing {
  configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = SourcesJar.Sources()))
  coordinates(artifactId = starterArtifactId)

  pom {
    name.set(starterArtifactId)
    description.set(
      "Spring Boot auto-configuration that runs a prometheus-proxy agent with the app and serves its metrics in-process.",
    )
    // The URL, license, developer, and SCM come from the root's gradle.properties, as the root project's do
  }

  publishToMavenCentral(automaticRelease = true)
  // As the root project does: unsigned when no GPG key is given, as for a local publish
  if (project.findProperty("signingInMemoryKey") != null) {
    signAllPublications()
  }
}
