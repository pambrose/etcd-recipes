plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    "implementation"(project(":etcd-recipes"))
}

tasks.shadowJar {
    archiveBaseName.set("etcd-recipes-test-runners")
    archiveClassifier.set("all")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "io.etcd.recipes.runners.MainKt"
    }
    mergeServiceFiles()
}
