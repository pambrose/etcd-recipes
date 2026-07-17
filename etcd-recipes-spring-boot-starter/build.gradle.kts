dependencies {
  api(project(":etcd-recipes-core"))
  api(libs.spring.boot.autoconfigure)
  // Actuator is optional — the health indicator only loads when it's on the app's classpath.
  compileOnly(libs.spring.boot.actuator)

  // starter-test brings ApplicationContextRunner + AssertJ (AssertableApplicationContext's supertype), version-aligned.
  testImplementation(libs.spring.boot.starter.test)
  testImplementation(libs.spring.boot.actuator)
}
