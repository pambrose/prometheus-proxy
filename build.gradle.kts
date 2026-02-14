import com.google.protobuf.gradle.id
import org.jmailen.gradle.kotlinter.tasks.FormatTask
import org.jmailen.gradle.kotlinter.tasks.LintTask
import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
  idea
  java
  `maven-publish`
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)   // Keep in sync with grpc
  alias(libs.plugins.kotlinter)
  alias(libs.plugins.versions)
  alias(libs.plugins.shadow)
  alias(libs.plugins.buildconfig)
  alias(libs.plugins.kover)
  alias(libs.plugins.detekt)
  // Turn these off until jacoco fixes their kotlin 1.5.0 SMAP issue
  // id("jacoco")
  // id("com.github.kt3k.coveralls") version "2.12.0"
}

group = "io.prometheus"
version = "3.0.0"

buildConfig {
  packageName("io.prometheus")
  buildConfigField("String", "APP_NAME", "\"${project.name}\"")
  buildConfigField("String", "APP_VERSION", "\"${project.version}\"")
  val formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")
  buildConfigField("String", "APP_RELEASE_DATE", "\"${LocalDate.now().format(formatter)}\"")
  buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
}

repositories {
  google()
  mavenCentral()
  maven { url = uri("https://jitpack.io") }
}

dependencies {
  implementation(platform(libs.kotlin.bom))
  implementation(libs.kotlin.reflect)

  implementation(libs.kotlinx.serialization)
  implementation(libs.kotlinx.datetime)

  implementation(platform(libs.grpc.bom))
  implementation(libs.bundles.grpc)

  implementation(platform(libs.ktor.bom))
  implementation(libs.bundles.ktor)

  implementation(platform(libs.common.utils.bom))
  implementation(libs.bundles.common.utils)

  implementation(libs.protobuf.kotlin)
  implementation(libs.grpc.kotlin.stub)

  // Required
  implementation(libs.netty.tcnative)

  implementation(libs.jetty.servlet)

  implementation(libs.annotation.api)
  implementation(libs.jcommander)
  implementation(libs.typesafe.config)

  implementation(libs.prometheus.simpleclient)

  implementation(libs.dropwizard.metrics)

  implementation(libs.zipkin.brave)

  implementation(libs.kotlin.logging)
  implementation(libs.logback.classic)
  implementation(libs.slf4j.jul)

  testImplementation(libs.kotest)
  testImplementation(libs.mockk)
  testImplementation(kotlin("test"))
}

configureKotlin()
configureGrpc()
configureJars()
configurePublishing()
configureTesting()
configureKotlinter()
configureDetekt()
configureVersions()
configureCoverage()
configureSecrets()

fun Project.configureKotlin() {
  tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
  }

  tasks.named("build") {
    mustRunAfter("clean")
  }

  configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
  }

  idea {
    module {
      isDownloadSources = true
      isDownloadJavadoc = true
    }
  }

  kotlin {
    jvmToolchain(17)

    compilerOptions {
      freeCompilerArgs.add("-Xreturn-value-checker=check")
    }

    sourceSets.all {
      listOf(
        "kotlin.time.ExperimentalTime",
        "kotlin.contracts.ExperimentalContracts",
        "kotlin.ExperimentalUnsignedTypes",
        "kotlinx.coroutines.ExperimentalCoroutinesApi",
        "kotlinx.coroutines.InternalCoroutinesApi",
        "kotlinx.coroutines.DelicateCoroutinesApi",
        "kotlin.concurrent.atomics.ExperimentalAtomicApi",
      ).forEach {
        languageSettings.optIn(it)
      }
    }
  }
}

fun Project.configureGrpc() {
  tasks.compileKotlin {
    dependsOn(":generateProto")
  }

  protobuf {
    protoc {
      artifact = "com.google.protobuf:protoc:${libs.versions.protoc.get()}"
    }
    plugins {
      id("grpc") {
        artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
      }
      id("grpckt") {
        artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.gengrpc.get()}:jdk8@jar"
      }
      id("kotlin")
    }
    generateProtoTasks {
      all().forEach { task ->
        task.plugins {
          id("kotlin")
          id("grpc")    // Generate Java gRPC classes
          id("grpckt")  // Generate Kotlin gRPC using the custom plugin from library
        }
      }
    }
  }
}

fun Project.configureJars() {
  // Required for multiple uberjar targets
  tasks.shadowJar {
    mergeServiceFiles()
  }

  val agentJar by tasks.registering(Jar::class) {
    dependsOn(tasks.shadowJar)
    archiveFileName.set("prometheus-agent.jar")
    manifest {
      attributes("Main-Class" to "io.prometheus.Agent")
    }
    from(zipTree(tasks.shadowJar.get().archiveFile))
  }

  val proxyJar by tasks.registering(Jar::class) {
    dependsOn(tasks.shadowJar)
    archiveFileName.set("prometheus-proxy.jar")
    manifest {
      attributes("Main-Class" to "io.prometheus.Proxy")
    }
    from(zipTree(tasks.shadowJar.get().archiveFile))
  }
}

fun Project.configureTesting() {
  tasks.test {
    useJUnitPlatform()

    testLogging {
      events("passed", "skipped", "failed", "standardOut", "standardError")
      exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
      showStandardStreams = true
    }
  }
}

fun Project.configurePublishing() {
  publishing {
    publications {
      create<MavenPublication>("mavenJava") {
        from(components["java"])
        versionMapping {
          usage("java-api") {
            fromResolutionOf("runtimeClasspath")
          }
          usage("java-runtime") {
            fromResolutionResult()
          }
        }
      }
    }
  }

  tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
  }

  val sourcesJar by tasks.registering(Jar::class) {
    dependsOn(tasks.classes)
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
  }

  val javadocJar by tasks.registering(Jar::class) {
    dependsOn(tasks.javadoc)
    archiveClassifier.set("javadoc")
    from(tasks.javadoc.get().destinationDir)
  }

  artifacts {
    archives(sourcesJar)
    //archives(javadocJar)
  }

  java {
    withSourcesJar()
  }
}

fun Project.configureKotlinter() {
  tasks.withType<LintTask> {
    // This will exclude all files under build/generated/
    this.source = this.source.minus(fileTree("build/generated")).asFileTree
  }
  tasks.withType<FormatTask> {
    this.source = this.source.minus(fileTree("build/generated")).asFileTree
  }

  kotlinter {
    ignoreFormatFailures = false
    ignoreLintFailures = false
    reporters = arrayOf("checkstyle", "plain")
  }
}

fun Project.configureDetekt() {
  detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$projectDir/etc/detekt/detekt.yml")
    baseline = file("$projectDir/etc/detekt/baseline.xml")
  }
}

fun Project.configureVersions() {
  fun isNonStable(version: String): Boolean {
    // val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val betaKeyword = listOf("-RC", "-BETA", "-ALPHA", "-M").any { version.uppercase().contains(it) }
    // val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = !betaKeyword // (stableKeyword || regex.matches(version)) && !betaKeyword
    return !isStable
  }

  tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
    rejectVersionIf {
      isNonStable(candidate.version)
    }
  }
}

fun Project.configureSecrets() {
  val secretsFile = file("secrets/secrets.env")
  if (secretsFile.exists()) {
    val envVars =
      secretsFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
          val idx = line.indexOf('=')
          if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
        }
        .toMap()

    tasks.withType<JavaExec> { environment(envVars) }
    tasks.withType<Test> { environment(envVars) }
  }
}

fun Project.configureCoverage() {
  kover {
    reports {
      filters {
        excludes {
          // Exclude the whole package from report statistics
          classes(
            "io.prometheus.grpc.*",
            "io.prometheus.grpc.**",
          )
        }
      }
    }
  }
}
