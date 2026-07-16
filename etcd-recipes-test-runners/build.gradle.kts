import org.gradle.kotlin.dsl.implementation

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":etcd-recipes"))
}

tasks.shadowJar {
    archiveBaseName.set("etcd-recipes-test-runners")
    archiveClassifier.set("all")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "io.etcd.recipes.runners.MainKt"
    }
    // Let all duplicate entries reach the transformers so they merge correctly. With the
    // default EXCLUDE strategy, Gradle drops duplicate META-INF/*.kotlin_module and
    // META-INF/services/* entries before the Kotlin-module/service-file transformers can
    // merge them (Shadow 9 warns about this).
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}
