dependencies {
  // api, not implementation: MicrometerEtcdMetrics implements core's EtcdMetrics and the
  // EtcdGauges binders take core recipe types, so callers need core on their compile
  // classpath. implementation publishes core at runtime scope only.
  api(project(":etcd-recipes-core"))
  api(libs.micrometer.core)
}
