import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.google.protobuf.gradle.id
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jmailen.gradle.kotlinter.tasks.FormatTask
import org.jmailen.gradle.kotlinter.tasks.LintTask
import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
  idea
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

// Version and group are defined in gradle.properties; also update version refs in README.md and website/srcref/docs/{api,getting-started}.md
providers.gradleProperty("overrideVersion").orNull?.let { version = it }

val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")
val releaseDate: String = providers.gradleProperty("releaseDate").orNull ?: LocalDate.now().format(formatter)
val buildTime: Long = providers.gradleProperty("buildTime").orNull?.toLong() ?: System.currentTimeMillis()

val basePackage = "io.prometheus"
val displayName = "Prometheus Proxy"
val repoUrl = "https://github.com/pambrose/prometheus-proxy"
val harnessConfigEnv = "HARNESS_CONFIG"
val generatedSourcesDir = "build/generated"
val detektConfigDir = "$projectDir/config/detekt"

buildConfig {
  packageName(basePackage)
  buildConfigField("String", "APP_NAME", "\"${project.name}\"")
  buildConfigField("String", "APP_VERSION", "\"${project.version}\"")
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
  testImplementation(libs.testcontainers)
  testImplementation(kotlin("test"))
}

configureKotlin()
configureTesting()
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

  tasks.wrapper {
    gradleVersion = libs.versions.gradle.wrapper.get()
    distributionType = Wrapper.DistributionType.BIN
  }

  idea {
    module {
      isDownloadSources = true
      isDownloadJavadoc = true
    }
  }

  kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())

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

fun Project.configureTesting() {
  tasks.withType<Test>().configureEach {
    val harnessConfig =
      providers.gradleProperty("harnessConfig").orNull
        ?: System.getenv(harnessConfigEnv)
    harnessConfig?.let { environment(harnessConfigEnv, it) }

    // Scale the test JVM heap to the harness load. LARGE+ generates multi-MB scrape payloads
    // and runs them through 2000+ sequential calls; the default 512m heap OOMs in bodyAsText().
    maxHeapSize =
      when (harnessConfig?.uppercase()) {
        "XXLARGE" -> "8g"
        "XLARGE" -> "4g"
        "LARGE" -> "2g"
        else -> "1g"
      }

    testLogging {
      showStandardStreams = false
    }
  }
}

fun Project.configureGrpc() {
  tasks.compileKotlin {
    dependsOn(tasks.named("generateProto"))
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
  // Disable the default shadowJar; we publish the standard Maven jar and
  // ship the two named fat jars below.
  tasks.shadowJar {
    enabled = false
  }

  val mainOutput = sourceSets.main.get().output
  val runtimeClasspath = configurations.runtimeClasspath

  // shadow 9.4.1's mergeServiceFiles() silently drops entries when multiple JARs ship a
  // same-named META-INF/services file (its append() transformer is broken the same way),
  // so without intervention the fat JAR loses grpc-core's DnsNameResolverProvider and
  // PickFirstLoadBalancerProvider and the gRPC client defaults to the `unix` scheme on
  // any non-IP hostname. Static service files under src/shadow/resources re-register the
  // missing providers; kept out of src/main/resources so the published Maven jar is clean.
  val shadowResources = file("src/shadow/resources")

  fun ShadowJar.configureFatJar(archiveName: String, mainClass: String) {
    archiveFileName.set(archiveName)
    manifest { attributes("Main-Class" to mainClass) }
    mergeServiceFiles()
    from(mainOutput)
    from(shadowResources)
    configurations.add(runtimeClasspath)
  }

  val agentJar by tasks.registering(ShadowJar::class) {
    configureFatJar("prometheus-agent.jar", "io.prometheus.Agent")
  }

  val proxyJar by tasks.registering(ShadowJar::class) {
    configureFatJar("prometheus-proxy.jar", "io.prometheus.Proxy")
  }

  tasks.named("assemble") {
    dependsOn(agentJar, proxyJar)
  }
}

fun Project.configureDokka() {
  dokka {
    moduleName.set(displayName)

    dokkaPublications.html {
      outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
      includes.from("docs/packages.md")
    }

    pluginsConfiguration.html {
      homepageLink.set(repoUrl)
      footerMessage.set(displayName)
    }

    dokkaSourceSets.main {
      documentedVisibilities(VisibilityModifier.Public)

      perPackageOption {
        matchingRegex.set("io\\.prometheus\\.grpc.*")
        suppress.set(true)
      }

      suppressedFiles.from("src/main/java/io/prometheus/common/ConfigVals.java")

      sourceLink {
        localDirectory.set(file("src/main/kotlin"))
        remoteUrl("$repoUrl/tree/master/src/main/kotlin")
        remoteLineSuffix.set("#L")
      }
    }
  }
}

fun Project.configurePublishing() {
  mavenPublishing {
    configure(
      KotlinJvm(
        javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
        sourcesJar = SourcesJar.Sources(),
      ),
    )

    pom {
      name.set(project.name)
      description.set("Enables Prometheus to scrape metrics from endpoints behind a firewall via a proxy/agent pair connected over gRPC.")
      url.set(repoUrl)
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
        url.set(repoUrl)
      }
    }

    publishToMavenCentral(automaticRelease = true)
    // Skip signing when no GPG key is provided (e.g., local publishing)
    if (project.findProperty("signingInMemoryKey") != null) {
      signAllPublications()
    }
  }
}

fun Project.configureKotlinter() {
  tasks.withType<LintTask> {
    // This will exclude all files under build/generated/
    this.source = this.source.minus(fileTree(generatedSourcesDir)).asFileTree
  }
  tasks.withType<FormatTask> {
    this.source = this.source.minus(fileTree(generatedSourcesDir)).asFileTree
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
    config.setFrom("$detektConfigDir/detekt.yml")
    baseline = file("$detektConfigDir/baseline.xml")
  }
}

fun Project.configureCoverage() {
  kover {
    reports {
      filters {
        excludes {
          classes(
            // Generated gRPC stubs (proto-derived; no value in measuring)
            "$basePackage.grpc.*",
            "$basePackage.grpc.**",
            // Generated by the buildconfig plugin
            "$basePackage.BuildConfig",
            // Generated by tscfg from config/config.conf
            "$basePackage.common.ConfigVals",
            "$basePackage.common.ConfigVals$*",
          )
        }
      }
      // Print a coverage summary to the console after `koverLog` runs.
      total {
        log {
          onCheck = true
          format = "<entity> line coverage: <value>%"
          coverageUnits = CoverageUnit.LINE
          aggregationForGroup = AggregationType.COVERED_PERCENTAGE
        }
      }
    }
  }
}
