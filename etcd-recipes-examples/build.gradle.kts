dependencies {
  implementation(project(":etcd-recipes-core"))

  // The examples' own helpers; the library's consumers do not get these
  implementation(libs.guava)
  implementation(libs.common.utils.core)
  implementation(libs.common.utils.guava)
  implementation(libs.kotlin.logging)
  runtimeOnly(libs.logback.classic)
}
