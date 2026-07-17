dependencies {
  api(project(":etcd-recipes-core"))
  // Declared inline rather than in the version catalog: Ktor is unique to this module, and the
  // catalog is otherwise reserved for shared dependencies.
  api("io.ktor:ktor-server-core:3.5.1")

  testImplementation("io.ktor:ktor-server-test-host:3.5.1")
}
