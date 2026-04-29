package kolt.build

import kolt.config.KoltConfig

fun autoInjectedTestDeps(config: KoltConfig): Map<String, String> {
  if (config.build.target != "jvm") return emptyMap()
  if (config.build.testSources.isEmpty() && config.testDependencies.isEmpty()) return emptyMap()
  return mapOf("org.jetbrains.kotlin:kotlin-test-junit5" to config.kotlin.version)
}

// JVM compile parity with `kotlinc` CLI: subprocess kotlinc auto-resolves
// `kotlin-stdlib` from `<kotlinc>/lib/` whenever `-no-stdlib` is not passed,
// but BTA's compile path does not — without an explicit classpath entry,
// even `println` fails to resolve. Auto-inject `kotlin-stdlib` matching
// the user's `[kotlin] version` so a `kolt new` project (zero deps) builds
// the same way under both backends. Native skips because konanc bundles
// stdlib in its distribution (ADR 0011). User-declared `kotlin-stdlib`
// wins via the `mainSeeds` merge in `resolveDependencies`.
fun autoInjectedMainDeps(config: KoltConfig): Map<String, String> {
  if (config.build.target != "jvm") return emptyMap()
  val stdlibGa = "org.jetbrains.kotlin:kotlin-stdlib"
  if (config.dependencies.containsKey(stdlibGa)) return emptyMap()
  return mapOf(stdlibGa to config.kotlin.version)
}
