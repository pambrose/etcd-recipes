import org.gradle.kotlin.dsl.implementation

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":etcd-recipes-core"))
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

    // INCLUDE (above) also keeps harmless duplicate metadata that no transformer merges:
    // per-dependency license text and Netty's per-module version stamp (jetcd pulls in ~14
    // Netty modules, each shipping the same-named file). None are read at runtime; drop them
    // so Shadow stops warning about duplicates.
    exclude("META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/io.netty.versions.properties")
}
