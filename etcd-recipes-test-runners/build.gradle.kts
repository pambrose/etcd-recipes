plugins {
    id("com.gradleup.shadow") version "9.4.1"
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
