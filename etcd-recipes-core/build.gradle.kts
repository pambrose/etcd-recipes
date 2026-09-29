dependencies {
  // api: these types appear in the public API — jetcd's Client and ByteSequence in every
  // recipe, kotlinx-serialization's Json and KSerializer in EtcdCodec, and Flow and suspend
  // functions in io.etcd.recipes.coroutines — so consumers need them on their compile
  // classpath. implementation would publish them at runtime scope only.
  api(libs.jetcd.core)
  api(libs.kotlinx.serialization.json)
  api(libs.kotlinx.coroutines.core)

  implementation(libs.guava)
  implementation(libs.common.utils.core)
  implementation(libs.common.utils.guava)
  // The SLF4J API only (through kotlin-logging): the application chooses the logging backend
  implementation(libs.kotlin.logging)

  // The library's tests include a container-based variant that reads result keys written by
  // the runners module. The runners module already depends on the library, so this is a
  // test-only one-way dependency, not a cycle.
  testImplementation(project(":etcd-recipes-test-runners"))
}
