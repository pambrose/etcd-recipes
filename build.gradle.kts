import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    jacoco
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ben.manes.versions) apply false
    alias(libs.plugins.coveralls) apply false
}

allprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "com.github.ben-manes.versions")
    apply(plugin = "jacoco")
    apply(plugin = "com.github.kt3k.coveralls")
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

        "testRuntimeOnly"(rootProject.libs.junit.jupiter.engine)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    val mainSourceSet = the<JavaPluginExtension>().sourceSets["main"]

    val sourcesJar by tasks.registering(Jar::class) {
        dependsOn("classes")
        archiveClassifier.set("sources")
        from(mainSourceSet.allSource)
    }

    val javadocTask = tasks.named<Javadoc>("javadoc")
    tasks.register<Jar>("javadocJar") {
        dependsOn(javadocTask)
        archiveClassifier.set("javadoc")
        from(javadocTask.map { it.destinationDir!! })
    }

    // Fixes a bizarre gradle error related to duplicate methods
    tasks.named<Jar>("jar") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    tasks.named("check") {
        dependsOn("jacocoTestReport")
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
        // connections from one spec don't interfere with the next one
        setForkEvery(1)
        testLogging {
            events("passed", "skipped", "failed", "standardOut", "standardError")
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = true
        }
    }
}
