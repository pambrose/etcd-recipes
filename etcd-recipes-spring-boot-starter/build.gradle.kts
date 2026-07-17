dependencies {
  api(project(":etcd-recipes-core"))
  // Declared inline rather than in the version catalog: these Spring deps are unique to this
  // module, and the catalog is otherwise reserved for shared dependencies.
  api("org.springframework.boot:spring-boot-autoconfigure:3.5.16")
  // Actuator is optional — the health indicator only loads when it's on the app's classpath.
  compileOnly("org.springframework.boot:spring-boot-actuator:3.5.16")

  // starter-test brings ApplicationContextRunner + AssertJ (AssertableApplicationContext's supertype), version-aligned.
  testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.16")
  testImplementation("org.springframework.boot:spring-boot-actuator:3.5.16")
}
