import com.google.protobuf.gradle.id
import org.jmailen.gradle.kotlinter.tasks.LintTask
import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
  val configVersion: String by System.getProperties()
  val detektVersion: String by System.getProperties()
//  val kotestPluginVersion: String by System.getProperties()
  val kotlinterVersion: String by System.getProperties()
  val kotlinVersion: String by System.getProperties()
  val koverVersion: String by System.getProperties()
  val protobufVersion: String by System.getProperties()
  val shadowVersion: String by System.getProperties()
  val versionsVersion: String by System.getProperties()

  idea
  java
  `maven-publish`
  kotlin("jvm") version kotlinVersion
  kotlin("plugin.serialization") version kotlinVersion
  id("com.google.protobuf") version protobufVersion   // Keep in sync with grpc
  id("org.jmailen.kotlinter") version kotlinterVersion
  id("com.github.ben-manes.versions") version versionsVersion
  id("com.github.johnrengelman.shadow") version shadowVersion
  id("com.github.gmazzo.buildconfig") version configVersion
  id("org.jetbrains.kotlinx.kover") version koverVersion
  id("io.gitlab.arturbosch.detekt") version detektVersion
  // Turn these off until jacoco fixes their kotlin 1.5.0 SMAP issue
  // id("jacoco")
  // id("com.github.kt3k.coveralls") version "2.12.0"
}

group = "io.prometheus"
version = "2.1.1"

buildConfig {
  packageName("io.prometheus")
  buildConfigField("String", "APP_NAME", "\"${project.name}\"")
  buildConfigField("String", "APP_VERSION", "\"${project.version}\"")
  val formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")
  buildConfigField("String", "APP_RELEASE_DATE", "\"${LocalDate.now().format(formatter)}\"")
  buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
}

repositories {
  mavenLocal()
  google()
  mavenCentral()
  maven { url = uri("https://jitpack.io") }
}

val annotationVersion: String by project
val datetimeVersion: String by project
val dropwizardVersion: String by project
val gengrpcVersion: String by project
val grpcVersion: String by project
val jcommanderVersion: String by project
val jettyVersion: String by project
val kluentVersion: String by project
val kotlinVersion: String by project
val ktorVersion: String by project
val logbackVersion: String by project
val loggingVersion: String by project
val tcnativeVersion: String by project
val prometheusVersion: String by project
val protobufVersion: String by project
val protocVersion: String by project
val serializationVersion: String by project
val slf4jVersion: String by project
val typesafeVersion: String by project
val utilsVersion: String by project
val zipkinVersion: String by project

dependencies {
  implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")

  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

  implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
  implementation("io.grpc:grpc-protobuf:$grpcVersion")
  implementation("io.grpc:grpc-stub:$grpcVersion")
  implementation("io.grpc:grpc-services:$grpcVersion")

//  implementation("com.google.protobuf:protobuf-java:$protobufVersion")
//  implementation("com.google.protobuf:protobuf-java-util:$protobufVersion")

  implementation("io.grpc:grpc-kotlin-stub:$gengrpcVersion")

  // Required
  implementation("io.netty:netty-tcnative-boringssl-static:$tcnativeVersion")

  implementation("com.github.pambrose.common-utils:core-utils:$utilsVersion")
  implementation("com.github.pambrose.common-utils:corex-utils:$utilsVersion")
  implementation("com.github.pambrose.common-utils:dropwizard-utils:$utilsVersion")
  implementation("com.github.pambrose.common-utils:guava-utils:$utilsVersion")
  implementation("com.github.pambrose.common-utils:grpc-utils:$utilsVersion")
  implementation("com.github.pambrose.common-utils:jetty-utils:$utilsVersion")
  implementation("com.github.pambrose.common-utils:ktor-client-utils:$utilsVersion")
  implementation("com.github.pambrose.common-utils:prometheus-utils:$utilsVersion")
  implementation("com.github.pambrose.common-utils:service-utils:$utilsVersion")
  implementation("com.github.pambrose.common-utils:zipkin-utils:$utilsVersion")

  implementation("org.eclipse.jetty:jetty-servlet:$jettyVersion")

  implementation("javax.annotation:javax.annotation-api:$annotationVersion")
  implementation("org.jcommander:jcommander:$jcommanderVersion")
  implementation("com.typesafe:config:$typesafeVersion")

  implementation("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")

  implementation("io.prometheus:simpleclient:$prometheusVersion")

  implementation("io.ktor:ktor-client:$ktorVersion")
  implementation("io.ktor:ktor-client-cio:$ktorVersion")
  implementation("io.ktor:ktor-client-auth:$ktorVersion")
  implementation("io.ktor:ktor-network:$ktorVersion")
  implementation("io.ktor:ktor-network-tls:$ktorVersion")

  implementation("io.ktor:ktor-server:$ktorVersion")
  implementation("io.ktor:ktor-server-cio:$ktorVersion")
  implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
  implementation("io.ktor:ktor-server-compression:$ktorVersion")

  implementation("io.dropwizard.metrics:metrics-healthchecks:$dropwizardVersion")

  implementation("io.zipkin.brave:brave-instrumentation-grpc:$zipkinVersion")

  implementation("io.github.oshai:kotlin-logging:$loggingVersion")
  implementation("ch.qos.logback:logback-classic:$logbackVersion")
  implementation("org.slf4j:jul-to-slf4j:$slf4jVersion")

  testImplementation("org.amshove.kluent:kluent:$kluentVersion")
  testImplementation(kotlin("test"))
}

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

tasks.compileKotlin {
  dependsOn(":generateProto")
}

protobuf {
  protoc {
    artifact = "com.google.protobuf:protoc:$protocVersion"
  }
  plugins {
    id("grpc") {
      artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
    }
    id("grpckt") {
      artifact = "io.grpc:protoc-gen-grpc-kotlin:$gengrpcVersion:jdk8@jar"
    }
  }
  generateProtoTasks {
    all().forEach { task ->
      task.plugins {
        id("grpc")    // Generate Java gRPC classes
        id("grpckt")  // Generate Kotlin gRPC using the custom plugin from library
      }
    }
  }
}

configurations.all {
  resolutionStrategy.cacheChangingModulesFor(0, "seconds")
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

detekt {
  buildUponDefaultConfig = true
  allRules = false
  config.setFrom("$projectDir/config/detekt/detekt.yml")
  baseline = file("$projectDir/config/detekt/baseline.xml")
}

tasks.withType<JavaCompile> {
  options.encoding = "UTF-8"
}

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

tasks.compileKotlin {
  dependsOn(":generateProto")
}

kotlin {
  jvmToolchain(11)

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

//tasks.withType<KotlinCompile> {
//  compilerOptions {
//    freeCompilerArgs = listOf("-Xbackend-threads=8")
//  }
//}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    events("passed", "skipped", "failed", "standardOut", "standardError")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    showStandardStreams = true
  }
}

tasks.withType<LintTask> {
  this.source = this.source.minus(fileTree("build/generated")).asFileTree

}

kotlinter {
  reporters = arrayOf("checkstyle", "plain")
}

idea {
  module {
    isDownloadSources = true
    isDownloadJavadoc = true
  }
}
