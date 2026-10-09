import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// The shared prefix (repo name + satellite-module prefix). The core library module itself is
// "$libraryName-core"; the -examples/-micrometer/-jackson/-spring-boot-starter/-ktor/-test-runners
// siblings keep the bare prefix.
val libraryName = "etcd-recipes"
val coreName = "$libraryName-core"
val libraryModule = ":$coreName"
val examplesModule = ":$libraryName-examples"
val micrometerModule = ":$libraryName-micrometer"
val jacksonModule = ":$libraryName-jackson"
val springBootStarterModule = ":$libraryName-spring-boot-starter"
val ktorModule = ":$libraryName-ktor"
val repoUrl = "https://github.com/pambrose/$libraryName"

val envDockerHost = "DOCKER_HOST"
val envTcDockerHost = "TESTCONTAINERS_DOCKER_HOST"

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ben.manes.versions)
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.maven.publish) apply false
}

// Version and group are defined in gradle.properties; also update the version refs in README.md
// and website/etcd-recipes/docs/{getting-started/index.md,integrations/index.md}
allprojects {
    providers.gradleProperty("overrideVersion").orNull?.let { version = it }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "org.jmailen.kotlinter")
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "org.jetbrains.dokka-javadoc")
    apply(plugin = "org.jetbrains.kotlinx.kover")
    apply(plugin = "dev.detekt")

    // Layer config/detekt/detekt.yml on top of detekt's bundled defaults so the
    // repo only has to spell out the rules it overrides (currently: MagicNumber off).
    configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.from(rootProject.file("config/detekt/detekt.yml"))
    }
}

// Root-level aggregation:
// - `./gradlew dokkaGenerate` covers both subprojects so the published
//   docs include API docs for the examples too.
// - `./gradlew koverHtmlReport` covers only the library; the examples
//   module is `main()` programs without tests and would otherwise drag
//   the aggregate coverage from ~70% down to ~45%.
dependencies {
    dokka(project(libraryModule))
    dokka(project(examplesModule))
    dokka(project(micrometerModule))
    dokka(project(jacksonModule))
    dokka(project(springBootStarterModule))
    dokka(project(ktorModule))

    kover(project(libraryModule))
}

configureDokka()
configureVersions()

subprojects {
    description = name

    // Only test dependencies are shared. Each module declares its own main dependencies in
    // its build file, so a published artifact's POM carries exactly what that module needs:
    // a blanket implementation list here once shipped jetcd at runtime scope in the core POM
    // (breaking consumers' compile) and a logging backend in every artifact.
    dependencies {
        "testImplementation"(rootProject.libs.bundles.testing)
        "testImplementation"(rootProject.libs.kotlin.logging)
        "testRuntimeOnly"(rootProject.libs.bundles.testing.runtime)
        // A logging backend for test output only; library artifacts depend on the SLF4J API alone
        "testRuntimeOnly"(rootProject.libs.logback.classic)
    }

    // The library and its optional bindings are published; the examples and test-runners aren't.
    if (name == coreName ||
        name == "$libraryName-micrometer" ||
        name == "$libraryName-jackson" ||
        name == "$libraryName-spring-boot-starter" ||
        name == "$libraryName-ktor"
    ) {
        configurePublishing()
    }

    configure<KotlinJvmProjectExtension> {
        jvmToolchain(rootProject.libs.versions.jvm.get().toInt())
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            // freeCompilerArgs.add("-Xreturn-value-checker=check")
            freeCompilerArgs.add("-Xcollection-literals")
            listOf(
                "kotlin.time.ExperimentalTime",
                "kotlin.ExperimentalUnsignedTypes",
                "kotlin.concurrent.atomics.ExperimentalAtomicApi",
            ).forEach { freeCompilerArgs.add("-opt-in=$it") }
        }
    }

    tasks.named<Test>("test") {
        useJUnitPlatform()
        // Backstop for a hang no bounded wait covers (the test helpers fail a stuck latch
        // after TEST_WAIT_LIMIT). Under CI's 45-minute job timeout, so a hung run fails this
        // task and still uploads its test reports instead of being cancelled without them.
        timeout.set(java.time.Duration.ofMinutes(40))
        // -PskipLincheck (CI) leaves out the Lincheck model checks: they're CPU-heavy, and on a
        // 2-core runner they pushed the full suite past the timeout above. They run locally.
        if (providers.gradleProperty("skipLincheck").isPresent) exclude("**/*LincheckTests*")
        // Fork a new JVM for each test class so background threads / etcd watch
        // connections from one spec don't interfere with the next one.
        forkEvery = 1
        // Run multiple test classes in parallel against the local etcd. Each
        // test namespaces its keys under its own path, so concurrent forks
        // do not collide. Cap at half the cores so etcd + coverage
        // instrumentation aren't starved.
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
        // Opt-in: -PuseTestcontainers makes each forked JVM start its own
        // ephemeral etcd container instead of hitting localhost:2379.
        // Bare -PuseTestcontainers (empty value) and -PuseTestcontainers=true both enable;
        // -PuseTestcontainers=false explicitly disables.
        val rawProp = providers.gradleProperty("useTestcontainers").orNull
        val useTestcontainers = rawProp != null && !rawProp.equals("false", ignoreCase = true)
        systemProperty("etcd.recipes.testcontainers", useTestcontainers.toString())
        if (useTestcontainers) {
            // Honor an explicit DOCKER_HOST / TESTCONTAINERS_DOCKER_HOST first.
            // We deliberately do NOT probe inside ~/Library/Containers/com.docker.docker/...
            // because reading that directory requires the macOS "App Management"
            // entitlement and triggers a TCC prompt every time Gradle reconfigures.
            // If you need the Docker Desktop "raw" socket, export it from your
            // shell, e.g.:
            //   export DOCKER_HOST="unix://$HOME/Library/Containers/com.docker.docker/Data/docker.raw.sock"
            val home = System.getProperty("user.home")
            val explicit = System.getenv(envTcDockerHost)
                ?: System.getenv(envDockerHost)
            val dockerHost = explicit
                ?: listOf(
                    "$home/.docker/run/docker.sock",
                    "/var/run/docker.sock",
                ).firstOrNull { File(it).exists() }?.let { "unix://$it" }

            if (dockerHost != null) {
                environment(envDockerHost, dockerHost)
                // TESTCONTAINERS_DOCKER_HOST overrides ~/.testcontainers.properties
                // when a stale config there pins docker.host to a missing socket.
                environment(envTcDockerHost, dockerHost)
                // Force the env-driven strategy so the locked-in
                // UnixSocketClientProviderStrategy from a stale user config
                // (which hardcodes /var/run/docker.sock) is ignored.
                environment(
                    "TESTCONTAINERS_DOCKER_CLIENT_STRATEGY",
                    "org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy",
                )
                // Enable Ryuk (the single sidecar reaper) and explicitly override
                // any ryuk.disabled=true left in ~/.testcontainers.properties — the
                // env var takes precedence. With Ryuk off, Testcontainers falls back
                // to JVMHookResourceReaper, whose per-JVM `docker prune` at shutdown
                // races across parallel forks and floods stderr with 409 "a prune
                // operation is already running". Ryuk avoids that; the explicit
                // stop()/close() shutdown hooks in the fixtures remain as
                // belt-and-suspenders cleanup.
                environment("TESTCONTAINERS_RYUK_DISABLED", "false")
                // -PtcPreferIpv6 (set by the Makefile's local Testcontainers targets):
                // resolve "localhost" to ::1 first. Testcontainers dials its Ryuk sidecar
                // at localhost:<port>, a port Docker picks; when an IDE or the Gradle
                // daemon already listens on 127.0.0.1 at that port, Docker Desktop still
                // publishes it on 0.0.0.0/[::], and an IPv4-first "localhost" reaches that
                // other process instead ("Could not connect to Ryuk"). Docker's [::] side
                // can't be intercepted that way. The etcd endpoints name 127.0.0.1
                // explicitly, so they are unaffected.
                if (providers.gradleProperty("tcPreferIpv6").isPresent) {
                    systemProperty("java.net.preferIPv6Addresses", "true")
                }
            }

            // The container-based tests (etcd-recipes-core/src/test/.../container) need
            // the test-runners fat JAR to mount into each participant container.
            // Wire it only for the library module's test task — the test-runners
            // module's own test task (if any) has no need for it.
            if (project.name == coreName) {
                // String-form dependsOn defers task-graph wiring until after all projects are
                // configured. The path itself is captured in a doFirst so that we look up the
                // (now-registered) shadowJar task at execution time — a configure-time lookup
                // would race the runners project's own configuration, and passing a Provider
                // to systemProperty just calls toString() on it.
                dependsOn(":$libraryName-test-runners:shadowJar")
                doFirst {
                    systemProperty(
                        "etcd.recipes.testRunnersJar",
                        project(":$libraryName-test-runners")
                            .tasks.named("shadowJar", Jar::class.java)
                            .get().archiveFile.get().asFile.absolutePath,
                    )
                }
            }
        }

        testLogging {
            events = setOf(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }
}

fun Project.configurePublishing() {
    // Dokka is already applied via the root subprojects { ... } block;
    // only maven-publish is project-specific to the published module.
    apply(plugin = "com.vanniktech.maven.publish")

    // A published module's public API is checked against its reference dump in api/, so a
    // change to it (say, a public property an IDE cleanup turned into a plain parameter)
    // fails until the dump is updated on purpose with `make api-dump`.
    extensions.configure<KotlinJvmProjectExtension> {
        @OptIn(ExperimentalAbiValidation::class)
        abiValidation()
    }

    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        configure(
            com.vanniktech.maven.publish.KotlinJvm(
                javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
                sourcesJar = SourcesJar.Sources(),
            ),
        )

        pom {
            name.set(project.name)
            description.set(provider { project.description })
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
                connection.set("scm:git:git://github.com/pambrose/etcd-recipes.git")
                developerConnection.set("scm:git:ssh://github.com/pambrose/etcd-recipes.git")
                url.set(repoUrl)
            }
        }

        publishToMavenCentral(automaticRelease = true)
        // Skip signing when no GPG key is provided (e.g., local publishing)
        if (providers.gradleProperty("signingInMemoryKey").isPresent) {
            signAllPublications()
        }
    }
}

fun Project.configureDokka() {
    dokka {
        pluginsConfiguration.html {
            homepageLink.set(repoUrl)
            footerMessage.set(libraryName)
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

    tasks.withType<DependencyUpdatesTask>().configureEach {
        notCompatibleWithConfigurationCache("the dependency updates plugin is not compatible with the configuration cache")
        // Reject a pre-release candidate only when the current version is stable. For
        // dependencies we intentionally track on a pre-release line (e.g. a detekt
        // alpha), newer pre-releases are still surfaced as available updates.
        rejectVersionIf {
            isNonStable(candidate.version) && !isNonStable(currentVersion)
        }
    }
}
