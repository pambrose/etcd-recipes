import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val libraryName = "etcd-recipes"
val libraryModule = ":$libraryName"
val examplesModule = ":$libraryName-examples"
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

// Version and group are defined in gradle.properties; also update version refs in README.md and website/srcref/docs/{api,getting-started}.md
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
    apply(plugin = "io.gitlab.arturbosch.detekt")
}

// Root-level aggregation: `./gradlew dokkaGenerate` and `koverHtmlReport`
// at the root produce one merged report across all subprojects.
dependencies {
    dokka(project(libraryModule))
    dokka(project(examplesModule))

    kover(project(libraryModule))
    kover(project(examplesModule))
}

dokka {
    pluginsConfiguration.html {
        homepageLink.set(repoUrl)
        footerMessage.set(libraryName)
    }
}

subprojects {
    description = name

    dependencies {
        "implementation"(rootProject.libs.kotlinx.serialization.json)
        "implementation"(rootProject.libs.kotlinx.coroutines.core)

        "implementation"(rootProject.libs.jetcd.core)

        "implementation"(rootProject.libs.guava)

        "implementation"(rootProject.libs.common.utils.core)
        "implementation"(rootProject.libs.common.utils.guava)

        "implementation"(rootProject.libs.kotlin.logging)
        "implementation"(rootProject.libs.logback.classic)

        "testImplementation"(rootProject.libs.bundles.testing)
        "testRuntimeOnly"(rootProject.libs.bundles.testing.runtime)
    }

    // Examples module isn't published; only the library is.
    if (name == libraryName) configurePublishing()

    configure<KotlinJvmProjectExtension> {
        jvmToolchain(rootProject.libs.versions.jvm.get().toInt())
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            listOf(
                "kotlin.time.ExperimentalTime",
                "kotlin.ExperimentalUnsignedTypes",
                "kotlin.concurrent.atomics.ExperimentalAtomicApi",
            ).forEach { freeCompilerArgs.add("-opt-in=$it") }
        }
    }

    tasks.named<Test>("test") {
        useJUnitPlatform()
        // Fork a new JVM for each test class so background threads / etcd watch
        // connections from one spec don't interfere with the next one.
        setForkEvery(1)
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
                // Disable Ryuk: on Docker Desktop the reaper container
                // tries to bind-mount the docker socket, which the engine
                // refuses with "operation not supported". We register an
                // explicit JVM shutdown hook in EtcdTestContainer instead.
                environment("TESTCONTAINERS_RYUK_DISABLED", "true")
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
                    id.set("etcd-recipes")
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

