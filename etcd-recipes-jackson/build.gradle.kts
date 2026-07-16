dependencies {
  "implementation"(project(":etcd-recipes"))
  // Declared inline rather than in the version catalog: this is the only module that
  // needs Jackson, and the catalog is otherwise reserved for shared dependencies.
  "api"("com.fasterxml.jackson.core:jackson-databind:2.22.1")
}
