import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

plugins {
    java
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ben.manes.versions) apply false
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.dokka.javadoc) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "com.github.ben-manes.versions")
    apply(plugin = "org.jmailen.kotlinter")
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "org.jetbrains.dokka-javadoc")
    apply(plugin = "org.jetbrains.kotlinx.kover")
    apply(plugin = "io.gitlab.arturbosch.detekt")
}

// Root-level Kover aggregation: `./gradlew koverHtmlReport` at the root
// produces one merged coverage report across all subprojects.
dependencies {
    "kover"(project(":etcd-recipes"))
    "kover"(project(":etcd-recipes-examples"))
}

subprojects {
    dependencies {
        "implementation"(rootProject.libs.kotlinx.serialization.json)
        "implementation"(rootProject.libs.kotlinx.coroutines.core)

        "implementation"(rootProject.libs.jetcd.core)

        "implementation"(rootProject.libs.guava)

        "implementation"(rootProject.libs.common.utils.core)
        "implementation"(rootProject.libs.common.utils.guava)

        "implementation"(rootProject.libs.kotlin.logging)
        "implementation"(rootProject.libs.logback.classic)

        "implementation"(rootProject.libs.netty.all)

        "testImplementation"(rootProject.libs.junit.jupiter.api)
        "testImplementation"(rootProject.libs.kotest.runner.junit5)
        "testImplementation"(rootProject.libs.kotest.assertions.core)
        "testImplementation"(rootProject.libs.testcontainers.core)

        "testRuntimeOnly"(rootProject.libs.junit.jupiter.engine)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    val mainSourceSet = the<JavaPluginExtension>().sourceSets["main"]

    val sourcesJar by tasks.registering(Jar::class) {
        dependsOn("classes")
        archiveClassifier.set("sources")
        from(mainSourceSet.allSource)
    }

    // Package Dokka's Javadoc-format output as the javadoc JAR so Maven
    // consumers see real KDoc instead of an empty Javadoc-on-Kotlin tree.
    val dokkaJavadocTask = tasks.named("dokkaGeneratePublicationJavadoc")
    tasks.register<Jar>("javadocJar") {
        dependsOn(dokkaJavadocTask)
        archiveClassifier.set("javadoc")
        from(dokkaJavadocTask)
    }

    // Fixes a bizarre gradle error related to duplicate methods
    tasks.named<Jar>("jar") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    artifacts {
        add("archives", sourcesJar)
    }

    configure<KotlinJvmProjectExtension> {
        jvmToolchain(17)
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xbackend-threads=8",
                "-opt-in=kotlin.time.ExperimentalTime",
                "-opt-in=kotlin.ExperimentalUnsignedTypes",
                "-opt-in=kotlin.concurrent.atomics.ExperimentalAtomicApi",
            )
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
            // Point Testcontainers at the first reachable Docker socket. We
            // prefer the Docker Desktop "raw" socket because the routing
            // socket at ~/.docker/run/docker.sock returns malformed /info
            // responses to docker-java even though plain curl/`docker` work.
            val home = System.getProperty("user.home")
            val candidates = listOf(
                "$home/Library/Containers/com.docker.docker/Data/docker.raw.sock",
                "$home/.docker/run/docker.sock",
                "/var/run/docker.sock",
            )
            candidates.firstOrNull { File(it).exists() }?.let { sock ->
                val dockerHost = "unix://$sock"
                environment("DOCKER_HOST", dockerHost)
                // TESTCONTAINERS_DOCKER_HOST overrides ~/.testcontainers.properties
                // when a stale config there pins docker.host to a missing socket.
                environment("TESTCONTAINERS_DOCKER_HOST", dockerHost)
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
