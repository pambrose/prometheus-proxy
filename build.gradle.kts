import com.google.protobuf.gradle.id
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jmailen.gradle.kotlinter.tasks.FormatTask
import org.jmailen.gradle.kotlinter.tasks.LintTask
import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
  idea
  java
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)   // Keep in sync with grpc
  alias(libs.plugins.shadow)
  alias(libs.plugins.buildconfig)
  alias(libs.plugins.kover)
  alias(libs.plugins.detekt)
  alias(libs.plugins.pambrose.envvar)
  alias(libs.plugins.pambrose.stable.versions)
  alias(libs.plugins.pambrose.kotlinter)
  alias(libs.plugins.pambrose.testing)
  alias(libs.plugins.dokka)
  alias(libs.plugins.maven.publish)
  alias(libs.plugins.taskinfo) apply false
}

version = findProperty("overrideVersion")?.toString() ?: "3.1.1"
group = "com.pambrose"

buildConfig {
  packageName("io.prometheus")
  buildConfigField("String", "APP_NAME", "\"${project.name}\"")
  buildConfigField("String", "APP_VERSION", "\"${project.version}\"")
  val formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")
  val releaseDate = (findProperty("overrideReleaseDate") as String?) ?: LocalDate.now().format(formatter)
  val buildTime = (findProperty("overrideBuildTime") as String?)?.toLong() ?: System.currentTimeMillis()
  buildConfigField("String", "APP_RELEASE_DATE", "\"$releaseDate\"")
  buildConfigField("long", "BUILD_TIME", "${buildTime}L")
}

dependencies {
  implementation(libs.kotlin.reflect)
  implementation(libs.kotlinx.serialization)
  implementation(libs.kotlinx.datetime)

  implementation(libs.bundles.grpc)
  implementation(libs.bundles.ktor)
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
configureDokka()
configurePublishing()
configureKotlinter()
configureDetekt()
configureCoverage()

fun Project.configureKotlin() {
  // Apply taskinfo only when not running inside IntelliJ sync
  if (!providers.systemProperty("idea.sync.active").isPresent) {
    apply(plugin = libs.plugins.taskinfo.get().pluginId)
  }

  tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
  }

  tasks.named("build") {
    mustRunAfter("clean")
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

fun Project.configureDokka() {
  dokka {
    moduleName.set("Prometheus Proxy")

    dokkaPublications.html {
      outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
      includes.from("docs/packages.md")
    }

    pluginsConfiguration.html {
      homepageLink.set("https://github.com/pambrose/prometheus-proxy")
      footerMessage.set("Prometheus Proxy")
    }

    dokkaSourceSets.main {
      documentedVisibilities(VisibilityModifier.Public)

      perPackageOption {
        matchingRegex.set("io\\.prometheus\\.grpc.*")
        suppress.set(true)
      }

      suppressedFiles.from("src/main/java/io/prometheus/common/ConfigVals.java")
      suppressedFiles.from("src/main/kotlin/io/prometheus/common/BaseOptions.kt")

      sourceLink {
        localDirectory.set(file("src/main/kotlin"))
        remoteUrl("https://github.com/pambrose/prometheus-proxy/tree/master/src/main/kotlin")
        remoteLineSuffix.set("#L")
      }
    }
  }
}

fun Project.configurePublishing() {
  mavenPublishing {
    configure(
      com.vanniktech.maven.publish.KotlinJvm(
        javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
        sourcesJar = SourcesJar.Sources(),
      ),
    )
    coordinates("com.pambrose", "prometheus-proxy", version.toString())

    pom {
      name.set("prometheus-proxy")
      description.set("Enables Prometheus to scrape metrics from endpoints behind a firewall via a proxy/agent pair connected over gRPC.")
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
    signAllPublications()
  }

  // Skip signing when no GPG key is provided (e.g., local publishing)
  tasks.withType<Sign>().configureEach {
    isEnabled = project.findProperty("signingInMemoryKey") != null
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
