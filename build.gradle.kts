import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.ApacheNoticeResourceTransformer
import com.github.jengelman.gradle.plugins.shadow.transformers.MergeLicenseResourceTransformer
import com.google.protobuf.gradle.id
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.withType
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jmailen.gradle.kotlinter.tasks.FormatTask
import org.jmailen.gradle.kotlinter.tasks.LintTask
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

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
  alias(libs.plugins.ben.manes.versions)
  alias(libs.plugins.pambrose.kotlinter)
  alias(libs.plugins.pambrose.testing)
  alias(libs.plugins.dokka)
  alias(libs.plugins.maven.publish)
  alias(libs.plugins.taskinfo) apply false
}

// Version and group are defined in gradle.properties; a bump also updates the files step 2 of docs/RELEASE.md lists
providers.gradleProperty("overrideVersion").orNull?.let { version = it }

// BUILD_TIME and APP_RELEASE_DATE are the time of the commit the build is from, so every build of a commit makes the
// same BuildConfig. Stamped with the current time, as they were, BuildConfig changed on every Gradle invocation, which
// recompiled main each time, dropped the configuration cache, and kept compileJava, compileTestKotlin, and the tests
// from ever being up to date or coming from the build cache. A ValueSource is read on every build
// (configuration-cache-safe), so a new commit restamps them. Without git, or outside a repository, it's the current
// time.
abstract class CommitTimeValueSource : ValueSource<Long, CommitTimeValueSource.Parameters> {
  interface Parameters : ValueSourceParameters {
    val repoDir: DirectoryProperty
  }

  @get:Inject
  abstract val execOperations: ExecOperations

  override fun obtain(): Long {
    val output = ByteArrayOutputStream()
    val commitSecs =
      runCatching {
        execOperations.exec {
          commandLine("git", "log", "-1", "--format=%ct")
          workingDir = parameters.repoDir.get().asFile
          standardOutput = output
          errorOutput = OutputStream.nullOutputStream()
          isIgnoreExitValue = true
        }
        output.toString().trim().toLongOrNull()
      }.getOrNull()
    return commitSecs?.let { it * 1000 } ?: System.currentTimeMillis()
  }
}

val buildTime = providers.of(CommitTimeValueSource::class) { parameters.repoDir.set(layout.projectDirectory) }
val releaseDate =
  buildTime.map { millis ->
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM/dd/yyyy"))
  }

val basePackage = "io.prometheus"
// The KDoc site's module name, which docs/packages.md's "# Module" heading must match. Not displayName, which inside a
// Project extension is Project.displayName ("root project 'prometheus-proxy'"), so packages.md never reached the site.
val dokkaModuleName = "Prometheus Proxy"
val repoUrl = "https://github.com/pambrose/prometheus-proxy"
val harnessConfigEnv = "HARNESS_CONFIG"
val generatedSourcesDir = "build/generated"
val detektConfigDir = "$projectDir/config/detekt"

// The two Test tasks a run of every spec is split between (configureTestPartitions).
val UNIT_TEST_TASK = "unitTest"
val HARNESS_TEST_TASK = "harnessTest"

// Coverage floors enforced by koverVerify; see configureCoverage() for why these values.
val MIN_LINE_COVERAGE_PCT = 95
val MIN_BRANCH_COVERAGE_PCT = 87

// Per-class line coverage floor (the perClass report variant in configureCoverage). The totals above leave a few
// points of headroom that a new, untested class could hide in; this catches it on its own. Set well below the
// lowest measured hand-written class (ProxyHttpConfig, 90.6%) so ordinary churn does not trip it.
val MIN_CLASS_LINE_COVERAGE_PCT = 80

buildConfig {
  packageName(basePackage)
  buildConfigField("String", "APP_NAME", "\"${project.name}\"")
  buildConfigField("String", "APP_VERSION", "\"${project.version}\"")
  buildConfigField("String", "APP_RELEASE_DATE", releaseDate.map { "\"$it\"" })
  buildConfigField("long", "BUILD_TIME", buildTime.map { "${it}L" })

  // The WebJar classpath layout embeds the version, so the dashboard's asset paths have to track the
  // dependency exactly. Sourcing them from the version catalog makes that structural rather than a
  // hand-maintained constant that goes stale on the next dependency bump and 404s the asset route.
  buildConfigField("String", "HTMX_VERSION", "\"${libs.versions.htmx.get()}\"")
  // The same for the Simple Icons WebJar the dashboard's brand icons are extracted from (configureDashboardIcons): the
  // URL they are served at carries it, so a browser's year-long cache of one never outlives a change to it.
  buildConfigField("String", "SIMPLE_ICONS_VERSION", "\"${libs.versions.simple.icons.get()}\"")
}

// By default the testing plugin also adds kotlin-test, kotest-runner-junit5, and logback-classic at its own versions,
// so the test classpath took the higher of each and the catalog's, and kotlin-test, with kotlin-test-junit5 and a JUnit
// Jupiter engine no spec uses, stayed after this build stopped declaring it. The catalog's are then the only versions.
pambroseTesting {
  addKotlinTest.set(false)
  addKotest.set(false)
  addLogbackClassic.set(false)
}

// The platforms netty-tcnative-boringssl-static has natives for (see dependencies).
val tcnativeClassifiers = listOf("linux-x86_64", "linux-aarch_64", "osx-x86_64", "osx-aarch_64", "windows-x86_64")

// What the standalone proxy and agent run with, beyond what the library publishes: Logback, their SLF4J provider. It's
// on the main runtime classpath, which the fat JARs take, but not in runtimeElements, which the POM and the Gradle module
// metadata publish, so an app that embeds the agent keeps its own provider (November 23 review, #9): a Logback the agent
// brought displaced it, and had Spring Boot fail to start an app on Log4j2, or ignore its Log4j2 config.
val standaloneRuntimeOnly = configurations.dependencyScope("standaloneRuntimeOnly")
configurations.runtimeClasspath { extendsFrom(standaloneRuntimeOnly.get()) }

dependencies {
  implementation(libs.kotlin.reflect)
  implementation(libs.kotlinx.serialization)

  implementation(libs.bundles.grpc)
  // Aligns every grpc-java module on libs.versions.toml's grpc. common-utils' grpc-utils brings grpc-netty and
  // grpc-inprocess at the grpc it was built with, and grpc-java's modules must all be the same version.
  implementation(platform(libs.grpc.bom))
  implementation(libs.bundles.ktor)
  implementation(libs.bundles.webjars)
  // Agent and Proxy, both public, extend service-utils' GenericService, so a compiler that sees either needs it, and
  // the guava-utils and Guava it brings at compile scope. api puts it on consumers' compile classpaths (compile scope in
  // the POM); with implementation, a Java build that embeds the agent couldn't compile a call to startAsyncAgent.
  api(libs.common.utils.service)
  implementation(libs.bundles.common.utils)
  implementation(libs.protobuf.kotlin)
  // Declares protobuf-java, and the protobuf-java-util grpc-services brings at 3.x, at protoc's version, so the
  // published POM pins them for builds that embed the agent: the code protoc generates needs a runtime at least that
  // new. See `protoc` in libs.versions.toml.
  runtimeOnly(libs.bundles.protobuf)
  implementation(libs.grpc.kotlin.stub)

  // BoringSSL bindings that let Netty use OpenSSL for TLS instead of the JDK. This artifact holds no native
  // code; the natives come in per-platform classifier jars, for Linux (x86_64, aarch_64), macOS (x86_64, aarch_64),
  // and Windows (x86_64). So the fat JARs, and builds that embed the agent, use OpenSSL on those platforms and fall
  // back to the JDK's TLS elsewhere. Declared to pin the tcnative version (see libs.versions.toml); runtimeOnly because
  // nothing references it at compile time.
  runtimeOnly(libs.netty.tcnative)
  // The natives, at the same version. common-utils' grpc-utils declares them at the tcnative it was built with, and
  // Maven tells a classifier jar apart from the plain one, so without these a Maven build took grpc-utils' natives
  // beside these classes. Gradle resolves every classifier at one version, so they change nothing here.
  tcnativeClassifiers.forEach { runtimeOnly(variantOf(libs.netty.tcnative) { classifier(it) }) }
  // Declares the Netty modules grpc-netty brings at the patched release, so the published POM and Gradle module pin it
  // for builds that embed the agent; the resolution override below changes only this build's own classpaths.
  runtimeOnly(libs.bundles.netty)

  implementation(libs.annotation.api)
  implementation(libs.jcommander)
  // Agent.startAsyncAgent takes a Typesafe Config, so a compiler that calls it needs the type: api puts it on consumers'
  // compile classpaths (compile scope in the POM).
  api(libs.typesafe.config)
  implementation(libs.prometheus.metrics.core)
  // EmbeddedAgentConfig.Builder.source and MetricsSource.of take a PrometheusRegistry, so a compiler that calls either
  // needs the type: api puts it on consumers' compile classpaths (compile scope in the POM).
  api(libs.prometheus.metrics.model)
  // The text formats the agent writes a registry it serves in-process in (MetricsSource.of). Declared, rather than left
  // to common-utils, which brings it at runtime only, so the published POM names it, at the catalog's version.
  implementation(libs.prometheus.metrics.exposition.textformats)
  // Aligns the Prometheus Java client modules common-utils brings with prometheus-metrics-core; see libs.versions.toml.
  implementation(platform(libs.prometheus.metrics.bom))
  implementation(libs.dropwizard.metrics)
  // Raises the Jackson that Dropwizard metrics-json brings (2.12.7) past its CVEs; see libs.versions.toml. The BOM aligns
  // this build and Gradle consumers, but Maven ignores a dependency's BOM imports, so the `jackson` bundle also declares
  // the modules at the patched version, which is what reaches a Maven build that embeds the agent.
  implementation(platform(libs.jackson.bom))
  runtimeOnly(libs.bundles.jackson)
  implementation(libs.zipkin.brave)

  implementation(libs.kotlin.logging)
  // Utils.setLogLevel sets a level through Logback when the app's SLF4J provider is Logback, which the library leaves to
  // the app; the standalone proxy and agent run with it (standaloneRuntimeOnly), and so do the tests
  compileOnly(libs.logback.classic)
  add(standaloneRuntimeOnly.name, libs.logback.classic)
  runtimeOnly(libs.slf4j.jul) // jul-to-slf4j bridge: installed at runtime, no compile-time references

  testImplementation(libs.kaml)
  testImplementation(libs.logback.classic)
  testImplementation(libs.kotest)
  testImplementation(libs.lincheck)
  testImplementation(libs.mockk)
  testImplementation(libs.testcontainers)

  // The Ktor plugin's coverage counts in this project's reports, so the coverage floors cover it too. Not the Spring
  // Boot starter's: it's in Java, and its tests run at Spring Boot's versions.
  kover(project(":ktor-plugin"))
}

// Raises every Netty module grpc-netty brings (4.2.17.Final) to the patched release in libs.versions.toml. netty-tcnative
// keeps its own pin there: its classes and the per-platform natives must match, which a Netty BOM would break by
// raising only some of them. Only the `netty` bundle reaches the published POM, so a Netty module on the runtime
// classpath that the bundle doesn't list fails the build instead of reaching embedders unpatched. Jackson gets the same
// check: the jackson-bom raises it on this build's classpaths, but only the `jackson` bundle reaches a Maven embedder.
val nettyVersion = libs.versions.netty.get()
val pinnedNettyModules = libs.bundles.netty.get().map { it.module.name }.toSet()
val pinnedJacksonModules = libs.bundles.jackson.get().map { it.module.name }.toSet()
configurations.configureEach {
  val isRuntimeClasspath = name == "runtimeClasspath"
  resolutionStrategy.eachDependency {
    if (requested.group == "io.netty" && !requested.name.startsWith("netty-tcnative")) {
      check(!isRuntimeClasspath || requested.name in pinnedNettyModules) {
        "${requested.module} is on the runtime classpath but not in the netty bundle in libs.versions.toml"
      }
      useVersion(nettyVersion)
      because("grpc-netty 1.85.1's Netty 4.2.17.Final has CVE-2026-93492")
    }
    if (isRuntimeClasspath && requested.group.startsWith("com.fasterxml.jackson") && requested.name != "jackson-bom") {
      check(requested.name in pinnedJacksonModules) {
        "${requested.module} is on the runtime classpath but not in the jackson bundle in libs.versions.toml"
      }
    }
  }
}

// jackson-annotations has had no patch number since Jackson 2.20 (2.23, not 2.23.0), so the `jackson` bundle declares it
// at its own version, which has to stay `jackson`'s major.minor, the one the jackson-bom gives it.
check(libs.versions.jacksonAnnotations.get() == libs.versions.jackson.get().substringBeforeLast('.')) {
  "jacksonAnnotations in libs.versions.toml should be the major.minor of jackson"
}

configureKotlin()
configureTesting()
configureGrpc()
configureDashboardIcons()
configureJars()
configureDokka()
configurePublishing()
configureKotlinter()
configureDetekt()
configureCoverage()
configureVersions()

fun Project.configureKotlin() {
  // taskinfo backs only `make tibuild` (its ti* tasks). Applying it on every build surfaced its call to
  // Project.getProperties, deprecated for removal in Gradle 10, as a build problem, so it loads only when a ti* task
  // is requested -- and never inside IntelliJ sync.
  val taskinfoRequested = gradle.startParameter.taskNames.any { it.substringAfterLast(':').startsWith("ti") }
  if (taskinfoRequested && !providers.systemProperty("idea.sync.active").isPresent) {
    apply(plugin = libs.plugins.taskinfo.get().pluginId)
  }

  tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
  }

  // No Java source uses its own source set's Kotlin classes: main's are the generated protobuf and gRPC classes and
  // ConfigVals, and test's are the docs' Java examples, which use only main's classes. The Kotlin plugin still puts each
  // source set's Kotlin classes on its Java compile's classpath, so each Java compile waited for the Kotlin one, about
  // 45s in CI's build job. Without them the two run at once; kotlinc reads the Java sources, so it never waited on javac.
  // Set once every project is evaluated, since the Kotlin plugin adds its classes in a configure action of its own that
  // would otherwise run after this one.
  // The classes and testClasses lifecycle tasks reached their Kotlin compiles only through the Java ones, so they depend
  // on them directly; the tasks that read the classes, the tests and the jars among them, find them through the source
  // sets' outputs either way.
  gradle.projectsEvaluated {
    tasks.named<JavaCompile>("compileJava") { classpath = sourceSets.main.get().compileClasspath }
    tasks.named<JavaCompile>("compileTestJava") { classpath = sourceSets.test.get().compileClasspath }
  }
  tasks.named("classes") { dependsOn("compileKotlin") }
  tasks.named("testClasses") { dependsOn("compileTestKotlin") }

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

  tasks.withType<KotlinCompile>().configureEach {
    val isMainCompile = name == "compileKotlin"
    compilerOptions {
      // Collection literals are a language-wide opt-in, so enable them for every
      // Kotlin source set (main + test) rather than per-task.
      freeCompilerArgs.addAll("-Xcollection-literals")

      // Run the unused-return-value checker over production code only. Kotest's
      // assertion DSL (e.g. shouldBe) returns its receiver, and tests intentionally
      // discard that result, so applying the checker to the test source set would
      // emit only false-positive warnings.
      if (isMainCompile) freeCompilerArgs.addAll("-Xreturn-value-checker=check")
    }
  }
}

fun Project.configureTesting() {
  val testConfigFiles = testConfigFiles()
  val checkedFiles = checkedFiles()

  tasks.withType<Test>().configureEach {
    // Main code compiles for Java 17 (`jvm` in libs.versions.toml), the floor the published artifact supports, but
    // the tests run on Java 25 (`testJvm`), the runtime the Docker images ship.
    javaLauncher.set(
      javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(libs.versions.testJvm.get())) },
    )

    val harnessConfig =
      providers.gradleProperty("harnessConfig").orNull
        ?: providers.environmentVariable(harnessConfigEnv).orNull
    harnessConfig?.let { environment(harnessConfigEnv, it) }

    // Gradle doesn't count a Test task's environment among its inputs, and BuildConfig is the same for every build of a
    // commit, so the tests can be up to date or come from the build cache: a result from one harness profile would be
    // reused for another, and the container specs' SKIPPED placeholders for a run that enables them. The profile is an
    // input, and so is whether the container specs run; a run that enables them always runs, since they test Docker
    // images Gradle can't see.
    inputs.property(harnessConfigEnv, harnessConfig.orEmpty())

    // Nor does it count the files a spec opens, only the classpath, so a change that edited only a workflow, the
    // Makefile, or docs/TESTING.md passed on the result of the run before it, up to date, or from the build cache after
    // a clean. The files the specs read are inputs of the tasks that run those specs; the docs, CI, and demo files only
    // of unitTest (and test), so an edit to them doesn't rerun the harness specs.
    inputs.files(testConfigFiles).withPropertyName("testConfigFiles").withPathSensitivity(PathSensitivity.RELATIVE)
    if (name != HARNESS_TEST_TASK)
      inputs.files(checkedFiles).withPropertyName("checkedFiles").withPathSensitivity(PathSensitivity.RELATIVE)

    val runContainerTests = providers.environmentVariable("RUN_CONTAINER_TESTS").orNull == "true"
    inputs.property("runContainerTests", runContainerTests)
    if (runContainerTests) {
      outputs.upToDateWhen { false }
      outputs.cacheIf("the container specs test Docker images, which aren't inputs") { false }
      // The images are built from the fat JARs, so the specs never test stale ones.
      dependsOn("agentJar", "proxyJar")
      // A run of only the container specs runs several at a time (ContainerTestsConfig); one with any other spec
      // doesn't, since those share the JVM's default registry and log capture. harnessTest never does: it holds only
      // the harness specs, which share fixed ports.
      if (name != HARNESS_TEST_TASK && testsOnlyContainerSpecs()) {
        systemProperty("kotest.framework.config.fqn", "io.prometheus.containers.support.ContainerTestsConfig")
      }
    }

    // Scale the test JVM heap to the harness load. LARGE+ generates multi-MB scrape payloads
    // and runs them through 2000+ sequential calls; the default 512m heap OOMs in bodyAsText().
    // Only the harness specs read the profile, so unitTest, which runs beside harnessTest, keeps 1g: given the profile's
    // heap too, the two JVMs could take 32g at XXLARGE.
    // An explicit -PtestMaxHeapSize=… (or TEST_MAX_HEAP_SIZE env var) overrides the load-based default —
    // useful for large `make scaling-tests` runs, which can hold thousands of scrape bodies at once.
    val testMaxHeapOverride =
      providers.gradleProperty("testMaxHeapSize").orNull
        ?: providers.environmentVariable("TEST_MAX_HEAP_SIZE").orNull
    maxHeapSize =
      testMaxHeapOverride
        ?: when (if (name == UNIT_TEST_TASK) null else harnessConfig?.uppercase()) {
          "XXLARGE" -> "16g"
          "XLARGE" -> "4g"
          "LARGE" -> "2g"
          else -> "1g"
        }

    // Kotest tag expression. The default leaves out the slow Lincheck specs; `make lincheck-tests` passes
    // -PkotestTags=Lincheck to run only them.
    systemProperty("kotest.tags", providers.gradleProperty("kotestTags").getOrElse("!Lincheck"))

    testLogging {
      showStandardStreams = false
    }
  }

  configureTestPartitions()
}

// The files outside the source sets that the specs read, by the tasks whose specs read them (configureTesting); see
// "Test Inputs" in docs/TESTING.md. A directory is taken whole, so a spec that reads a new file in it is covered.

// The test configs and TLS certificates the harness specs and others load.
fun Project.testConfigFiles(): FileCollection = files(fileTree("config/test-configs"), fileTree("testing"))

// What the specs that check the docs, CI, and demos against the code read: misc/, TestingGuideTest among them, and
// TestPortsTest. Not the code reviews, which no spec reads, or what a demo's build or demo.sh writes.
fun Project.checkedFiles(): FileCollection =
  files(
    "Makefile",
    "README.md",
    "CHANGELOG.md",
    "RELEASE_NOTES.md",
    "AGENTS.md",
    "llms.txt",
    "gradle.properties",
    "settings.gradle.kts",
    "config/config.conf",
    fileTree(".github"),
    fileTree("docs") { exclude("**/CODE_REVIEW_*.md") },
    fileTree("website/prometheus-proxy/docs"),
    "website/prometheus-proxy/zensical.toml",
    fileTree("demos") { exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/target/**", "*/docker/.env*") },
    fileTree("examples"),
    fileTree("etc"),
    fileTree("grafana"),
    fileTree("bin"),
    fileTree("logback"),
    fileTree("src/shadow/resources"),
    // The sources' text, which some specs scan, comments and KDoc included, the docs site's snippet files beside the
    // test sources, which nothing compiles, and the subprojects', whose specs and port constants are checked too.
    fileTree("src/main/kotlin"),
    fileTree("src/test/kotlin"),
    fileTree("src/test/java"),
    fileTree("ktor-plugin/src"),
    fileTree("spring-boot-starter/src"),
  )

// The harness specs bind fixed ports (TestPorts' HARNESS_* ports, and each spec's own), so they run one at a time; the
// other specs bind none of theirs, which TestPortsTest checks. A run of every spec therefore splits them between two
// Test tasks, unitTest and harnessTest, which Gradle runs at the same time, each in a JVM of its own, so neither shares
// the default registry or a log capture with the other. test runs neither itself, only both, so `./gradlew test`, check,
// build, and the Kover tasks still run every spec. A run that names specs with --tests, as IntelliJ's do, or excludes
// test with -x test, leaves test as it was: the specs it runs, in one JVM, or none. Either partition can still be named
// on its own, filtered or not, as `./gradlew unitTest --tests …` is.

// The specs the command line names with --tests, in either form Gradle takes: `--tests X` and `--tests=X`.
fun Project.namedSpecs(): List<String> =
  gradle.startParameter.taskRequests.flatMap { request ->
    request.args.zipWithNext().filter { (option, _) -> option == "--tests" }.map { (_, spec) -> spec } +
      request.args.filter { it.startsWith("--tests=") }.map { it.removePrefix("--tests=") }
  }

// Whether the command line names specs, and every one it names is a container spec, as `make container-tests`,
// `make scaling-tests`, and container-tests.yml do.
fun Project.testsOnlyContainerSpecs(): Boolean {
  val named = namedSpecs()
  return named.isNotEmpty() && named.all { it.startsWith("io.prometheus.containers.") }
}

fun Project.configureTestPartitions() {
  // Partitioned by class file, which picks the specs Gradle hands Kotest, rather than by test filter: Kotest takes a
  // filter's include patterns and ignores its excludes, so an excluding filter ran every spec.
  val harnessClasses = "io/prometheus/harness/**"
  val testSourceSet = sourceSets.test.get()

  fun registerPartition(
    name: String,
    description: String,
    selectClasses: Test.() -> Unit,
  ) = tasks.register<Test>(name) {
    this.description = description
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    selectClasses()
  }

  val unitTest = registerPartition(UNIT_TEST_TASK, "Runs every spec outside the harness.") { exclude(harnessClasses) }
  val harnessTest =
    registerPartition(HARNESS_TEST_TASK, "Runs the harness specs, one at a time.") { include(harnessClasses) }

  val testExcluded = gradle.startParameter.excludedTaskNames.any { it == "test" || it == ":test" }
  if (namedSpecs().isNotEmpty() || testExcluded) {
    // test runs as it is. The Kover tasks leave the partitions out, as they'd otherwise run them whole: `./gradlew
    // build -x test` would run every spec.
    kover { currentProject { instrumentation { disabledForTestTasks.addAll(UNIT_TEST_TASK, HARNESS_TEST_TASK) } } }
    return
  }

  tasks.test {
    enabled = false
    dependsOn(unitTest, harnessTest)
  }
  // Kover leaves a disabled Test task out of its reports anyway; this says so.
  kover { currentProject { instrumentation { disabledForTestTasks.add("test") } } }
}

fun Project.configureGrpc() {
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

// The services the dashboard icons' extraction uses, injected, since its sources are read after configuration, when the
// configuration cache leaves no Project to call.
interface DashboardIconServices {
  @get:Inject
  val archives: ArchiveOperations
}

fun Project.configureDashboardIcons() {
  // The brand marks the dashboard's Links column can show, by Simple Icons slug: one for each LinkIcon, which reads
  // each from the resource path below. PathLinkTest fails on a LinkIcon whose mark isn't on the classpath.
  val slugs = listOf("prometheus", "grafana")
  val version = libs.versions.simple.icons.get()
  val webJarDir = "META-INF/resources/webjars/simple-icons/$version/icons"
  val resourceDir = "io/prometheus/proxy/dashboard/icons"
  val outputDir = layout.buildDirectory.dir("generated/dashboard-icons")
  val archives = objects.newInstance<DashboardIconServices>().archives

  // The Simple Icons WebJar's own configurations: one to declare it in, and one to resolve it with, without its
  // dependencies. Nothing extends or publishes them, so the 7.5 MB WebJar reaches neither the runtime classpath, the
  // POM, nor the fat JARs; only the SVGs extracted below do, as main resources.
  val iconWebJar = configurations.dependencyScope("dashboardIcons")
  val iconWebJarFiles =
    configurations.resolvable("dashboardIconsClasspath") {
      extendsFrom(iconWebJar.get())
      isTransitive = false
    }
  dependencies.add(iconWebJar.name, libs.webjar.simple.icons)

  val extractDashboardIcons =
    tasks.register<Sync>("extractDashboardIcons") {
      description = "Extracts the dashboard's brand icons from the Simple Icons WebJar."
      from(iconWebJarFiles.map { jars -> jars.map { archives.zipTree(it) } }) {
        include(slugs.map { "$webJarDir/$it.svg" })
        eachFile { path = "$resourceDir/$name" }
      }
      into(outputDir)
      includeEmptyDirs = false
      // Simple Icons removes brands in major versions, and a mark missing from the classpath would leave its links
      // blank, so a bump that drops one fails here.
      doLast {
        val icons = outputDir.get().dir(resourceDir).asFile
        val missing = slugs.filterNot { icons.resolve("$it.svg").isFile }
        check(missing.isEmpty()) { "simple-icons $version has no icon for $missing; see LinkIcon" }
      }
    }

  sourceSets.main { resources.srcDir(extractDashboardIcons) }
}

fun Project.configureJars() {
  // Disable the default shadowJar; we publish the standard Maven jar and
  // ship the two named fat jars below.
  tasks.shadowJar {
    enabled = false
  }

  // The Shadow plugin still adds its shadowRuntimeElements variant to the java component. Published, it had no files,
  // so a Gradle consumer that asked for shadowed or embedded bundling selected it and got nothing on its classpath.
  components.named<AdhocComponentWithVariants>("java") {
    withVariantsFromConfiguration(configurations["shadowRuntimeElements"]) { skip() }
  }

  val mainOutput = sourceSets.main.get().output
  val runtimeClasspath = configurations.runtimeClasspath

  // Under ShadowJar's default EXCLUDE duplicates strategy, mergeServiceFiles() loses
  // entries when multiple JARs ship a same-named META-INF/services file — the fat JAR
  // then lacks grpc-core's DnsNameResolverProvider and the gRPC client defaults to the
  // `unix` scheme on any non-IP hostname. The filesMatching() block below fixes the
  // merge itself; the static service files under src/shadow/resources additionally
  // pin the critical grpc providers as a guard against future Shadow regressions.
  // logback.xml is there too: in the published library jar, it would replace the logging config of the application
  // that embeds the agent. Kept out of src/main/resources so the published Maven jar is clean.
  val shadowResources = file("src/shadow/resources")

  // The NOTICE and license files the transformers below merge. Matched exactly, since a path let through as INCLUDE
  // that no transformer takes ends up in the JAR once per dependency that ships it.
  val noticePaths = listOf("META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/NOTICE.md", "META-INF/notice.md")
  val licensePaths =
    listOf("META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/LICENSE.md", "LICENSE", "LICENSE.txt", "LICENSE.md")

  fun ShadowJar.configureFatJar(archiveName: String, mainClass: String) {
    archiveFileName.set(archiveName)
    // Enable-Native-Access gives the jar's classes native access under java -jar, which Java 22 and later honor and
    // older JDKs ignore, so every launcher runs without Java 24's restricted-method warning and no flag to pass.
    manifest { attributes("Main-Class" to mainClass, "Enable-Native-Access" to "ALL-UNNAMED") }
    mergeServiceFiles()
    // First-wins kept one dependency's NOTICE and one LICENSE of each name, where the Apache License's section 4(d)
    // requires every NOTICE, and MIT and the like require their own license text (October #40). Every NOTICE is
    // merged into META-INF/NOTICE, and every license text follows the project's own in META-INF/LICENSE.
    transform<ApacheNoticeResourceTransformer> {
      projectName.set("prometheus-proxy")
      organizationName.set("Paul Ambrose")
      organizationURL.set("https://github.com/pambrose/prometheus-proxy")
      inceptionYear.set("2017")
    }
    transform<MergeLicenseResourceTransformer> {
      artifactLicense.set(rootProject.layout.projectDirectory.file("LICENSE.txt"))
    }
    // ShadowJar's default DuplicatesStrategy (EXCLUDE) drops duplicate entries before the
    // merging transformers run, so allow duplicates through on the transformer-matched
    // paths only; everything else keeps first-wins EXCLUDE semantics.
    filesMatching(buildList {
      addAll(listOf("META-INF/services/**", "META-INF/*.kotlin_module"))
      addAll(noticePaths)
      addAll(licensePaths)
    }) {
      duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    from(mainOutput)
    from(shadowResources)
    configurations.add(runtimeClasspath)
  }

  val agentJar = tasks.register<ShadowJar>("agentJar") {
    configureFatJar("prometheus-agent.jar", "io.prometheus.agent.AgentLauncher")
  }

  val proxyJar = tasks.register<ShadowJar>("proxyJar") {
    configureFatJar("prometheus-proxy.jar", "io.prometheus.proxy.ProxyLauncher")
  }

  tasks.named("assemble") {
    dependsOn(agentJar, proxyJar)
  }
}

fun Project.configureDokka() {
  dokka {
    moduleName.set(dokkaModuleName)

    dokkaPublications.html {
      outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    }

    pluginsConfiguration.html {
      homepageLink.set(repoUrl)
      footerMessage.set(dokkaModuleName)
    }

    dokkaSourceSets.main {
      documentedVisibilities(VisibilityModifier.Public)

      // The module's and packages' descriptions. A source set's includes, since a publication's are for a multi-module
      // page, which a single module has none of.
      includes.from("docs/packages.md")

      // Agent and Proxy extend common-utils' AbstractGenericService, whose private dropwizardExports field has a type from
      // a runtime-only dependency, DropwizardExports, which the compile classpath Dokka reads lacks. Nothing public
      // names it, so this is only for Dokka; drop it once common-utils types the field by an interface (MultiCollector).
      classpath.from(configurations.runtimeClasspath)

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

    // The URL, license, developer, and SCM come from gradle.properties' POM_* keys, which every published project shares
    pom {
      name.set(project.name)
      description.set("Enables Prometheus to scrape metrics from endpoints behind a firewall via a proxy/agent pair connected over gRPC.")
    }

    publishToMavenCentral(automaticRelease = true)
    // Skip signing when no GPG key is provided (e.g., local publishing)
    if (project.findProperty("signingInMemoryKey") != null) {
      signAllPublications()
    }
  }

  // A variant of the published Gradle module with no files resolves to nothing for a consumer that selects it, as the
  // Shadow plugin's did (see configureJars), so every publish, local or to Maven Central, fails on one.
  tasks.withType<GenerateModuleMetadata>().configureEach {
    val moduleFile = outputFile
    doLast {
      val module = groovy.json.JsonSlurper().parse(moduleFile.get().asFile) as Map<*, *>
      val empty =
        (module["variants"] as List<*>)
          .map { it as Map<*, *> }
          .filter { (it["files"] as List<*>?).isNullOrEmpty() }
          .map { it["name"] }
      check(empty.isEmpty()) { "The published Gradle module has variants with no files: $empty" }
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
    // A second report variant over the same JVM test coverage, so the per-class rule below can have filters of its
    // own: Kover's verification rules cannot be filtered individually.
    currentProject {
      createVariant("perClass") {
        add("jvm")
      }
    }

    reports {
      filters {
        excludes {
          classes(
            // Generated gRPC stubs (proto-derived; no value in measuring)
            "$basePackage.grpc.*",
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

        // Floors that catch a slide, not targets to chase. Codecov already gates a PR on dropping more
        // than a point from its base, but that only runs in CI and only on a PR; these fail the build
        // locally too, which is where a regression is cheapest to notice.
        //
        // Set below the measured values (line 97.4%, branch 90.4% as of this commit) so ordinary churn
        // does not break the build: deleting a well-covered file moves the total without anything getting
        // worse. Branch gets the wider margin because it is the noisier of the two -- one new `when` with
        // several arms shifts it further than a comparable number of new lines shifts line coverage.
        // Raise them when the real numbers have moved up and stayed there.
        verify {
          // Deliberately NOT onCheck. koverVerify has no way to tell "coverage genuinely regressed" from
          // "no tests ran", so wiring it into `check` fails at 0% for every legitimate test-less
          // invocation -- `./gradlew build -x test` in AGENTS.md and `make build`'s `clean build -x test` among
          // them. Each would need its own -x koverVerify, which is four places to forget. CI runs this
          // task explicitly alongside the suite (see ci.yml), and `make coverage-verify` runs it locally.
          onCheck = false
          rule("Line coverage floor") {
            minBound(MIN_LINE_COVERAGE_PCT, CoverageUnit.LINE, AggregationType.COVERED_PERCENTAGE)
          }
          rule("Branch coverage floor") {
            minBound(MIN_BRANCH_COVERAGE_PCT, CoverageUnit.BRANCH, AggregationType.COVERED_PERCENTAGE)
          }
        }
      }

      // Every handwritten class must clear MIN_CLASS_LINE_COVERAGE_PCT on its own, so a new untested class fails the
      // build instead of disappearing into the totals.
      variant("perClass") {
        filtersAppend {
          excludes {
            classes(
              // Compiler-generated classes (lambdas, coroutine bodies, SAM adapters) are named Outer$method$N, and
              // Kover counts each as a class: a one-line error-handler lambda at 0% would fail the floor while its
              // enclosing class is fully tested. Their lines still count toward the total floors above.
              "*$*$*",
              // JVM entry points (main, startSyncAgent) start a whole process and block, so tests use the embedded
              // entry point instead. The fat JARs' launchers only run in the forked JVMs of BaseOptionsTest.
              $$"$$basePackage.Proxy$Companion",
              $$"$$basePackage.Agent$Companion",
              "$basePackage.agent.AgentLauncher",
              "$basePackage.proxy.ProxyLauncher",
            )
          }
        }
        verify {
          onCheck = false
          rule("Per-class line coverage floor") {
            groupBy = GroupingEntityType.CLASS
            minBound(MIN_CLASS_LINE_COVERAGE_PCT, CoverageUnit.LINE, AggregationType.COVERED_PERCENTAGE)
          }
        }
      }
    }
  }
}

fun Project.configureVersions() {
  // A pre-release qualifier is a `.` or `-` delimiter followed by a known unstable
  // keyword. `m\d` matches milestones (`-M1`/`.M2`) without catching stable classifiers
  // like `-macos`/`-MR1`, and the `[.-]` delimiter catches both dash-style (`-alpha`)
  // and dot-style (Netty's `.Beta1`) qualifiers while leaving `-jre`/`.Final` stable.
  val preReleaseQualifier =
    Regex("""[.-](rc|beta|alpha|m\d|cr|snapshot|eap|dev|milestone|pre)""", RegexOption.IGNORE_CASE)

  fun isNonStable(version: String): Boolean = preReleaseQualifier.containsMatchIn(version)

  // A full Git commit hash. grpc-kotlin published one to Maven Central as a version
  // (6f774052…), which the plugin ranks above every numbered release.
  val commitHash = Regex("""[0-9a-f]{40}""", RegexOption.IGNORE_CASE)

  tasks.withType<DependencyUpdatesTask>().configureEach {
    notCompatibleWithConfigurationCache("the dependency updates plugin is not compatible with the configuration cache")
    // Reject a commit-hash candidate always, and a pre-release candidate only when the
    // current version is stable. For dependencies we intentionally track on a pre-release
    // line (e.g. a detekt alpha), newer pre-releases are still surfaced as available updates.
    rejectVersionIf {
      commitHash.matches(candidate.version) ||
        (isNonStable(candidate.version) && !isNonStable(currentVersion))
    }

    // Prints only what is behind, and what could not be checked. The plugin's own report, which `make versions-all`
    // asks for with --output-formatter=plain, first lists every dependency already up to date.
    outputFormatter {
      val behind = outdated.dependencies.sortedBy { "${it.group}:${it.name}" }
      if (behind.isEmpty()) println("All dependencies are up to date")
      behind.forEach { d ->
        val latest = with(d.available) { release ?: milestone ?: integration }
        println(" - ${d.group}:${d.name} ${d.version} -> $latest")
      }
      unresolved.dependencies.sortedBy { "${it.group}:${it.name}" }.forEach {
        println(" ? ${it.group}:${it.name}:${it.version} could not be checked")
      }
      if (gradle.current.isUpdateAvailable) {
        println(" - Gradle ${gradle.running.version} -> ${gradle.current.version}")
      }
    }
  }
}
