dependencies {
  // api, not implementation: JacksonCodec implements core's EtcdCodec, and callers need
  // core on their compile classpath. implementation publishes core at runtime scope only.
  api(project(":etcd-recipes-core"))
  api(libs.jackson.databind)
}
