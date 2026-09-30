dependencies {
  api(project(":etcd-recipes-core"))
  api(libs.spring.boot.autoconfigure)
  // Spring binds EtcdProperties (an all-defaults Kotlin data class) through kotlin-reflect; declared
  // here so it doesn't arrive only by way of another library.
  implementation(libs.kotlin.reflect)
  // Actuator is optional — the health indicator only loads when it's on the app's classpath.
  compileOnly(libs.spring.boot.actuator)
  compileOnly(libs.spring.boot.health)

  // starter-test brings ApplicationContextRunner + AssertJ (AssertableApplicationContext's supertype), version-aligned.
  testImplementation(libs.spring.boot.starter.test)
  testImplementation(libs.spring.boot.actuator)
  testImplementation(libs.spring.boot.health)
}
