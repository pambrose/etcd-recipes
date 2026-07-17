dependencies {
  api(project(":etcd-recipes-core"))
  api(libs.ktor.server.core)

  testImplementation(libs.ktor.server.test.host)
}
