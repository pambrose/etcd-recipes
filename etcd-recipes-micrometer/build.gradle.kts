dependencies {
  "implementation"(project(":etcd-recipes"))
  // Declared inline rather than in the version catalog: this is the only module that
  // needs Micrometer, and the catalog is otherwise reserved for shared dependencies.
  "api"("io.micrometer:micrometer-core:1.14.2")
}
