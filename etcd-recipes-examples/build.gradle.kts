dependencies {
  implementation(project(":etcd-recipes-core"))
  implementation(libs.kotlin.logging)
  runtimeOnly(libs.logback.classic)
}
