package kolt.cli

import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// `kolt deps tree` must render main and test closures as separate
// sections and suppress the test section when the auto-inject skip
// kicks in (empty test_sources + empty [test-dependencies] on JVM).
// Tests exercise the pure seed decider so assertions do not depend on
// POM network fetches.
class DepsTreeSeedsTest {

  @Test
  fun bothSectionsPopulatedWhenMainAndTestDeclared() {
    val config =
      testConfig(
        target = "jvm",
        dependencies = mapOf("com.example:lib" to "1.0"),
        testDependencies = mapOf("com.example:junit-ext" to "2.0"),
        testSources = listOf("test"),
      )

    val seeds = depsTreeSeeds(config)

    assertTrue(
      "com.example:lib" in seeds.mainSeeds,
      "main section must carry declared deps: ${seeds.mainSeeds}",
    )
    assertTrue(
      "org.jetbrains.kotlin:kotlin-stdlib" in seeds.mainSeeds,
      "JVM main section must carry the auto-injected kotlin-stdlib: ${seeds.mainSeeds}",
    )
    assertTrue(
      "com.example:junit-ext" in seeds.testSeeds,
      "test section must carry declared test deps: ${seeds.testSeeds}",
    )
    assertTrue(
      "org.jetbrains.kotlin:kotlin-test-junit5" in seeds.testSeeds,
      "JVM test section must also carry the auto-injected kotlin-test-junit5: ${seeds.testSeeds}",
    )
    assertFalse(seeds.isEmpty)
  }

  @Test
  fun testSectionEmptyWhenAutoInjectSkipsAndNoTestDepsDeclared() {
    // test_sources empty + test-dependencies empty on JVM: the
    // kotlin-test-junit5 auto-inject is skipped so the test section is
    // empty. `kolt deps tree` must suppress the "test dependencies:"
    // header in that case (doTree's `seeds.testSeeds.isNotEmpty()` gate).
    val config =
      testConfig(
        target = "jvm",
        dependencies = mapOf("com.example:lib" to "1.0"),
        testDependencies = emptyMap(),
        testSources = emptyList(),
      )

    val seeds = depsTreeSeeds(config)

    assertTrue("com.example:lib" in seeds.mainSeeds)
    assertTrue("org.jetbrains.kotlin:kotlin-stdlib" in seeds.mainSeeds)
    assertTrue(seeds.testSeeds.isEmpty(), "test section must be empty: ${seeds.testSeeds}")
  }

  @Test
  fun testSectionCarriesOnlyAutoInjectedJunitWhenTestSourcesPresent() {
    val config =
      testConfig(
        target = "jvm",
        dependencies = emptyMap(),
        testDependencies = emptyMap(),
        testSources = listOf("test"),
      )

    val seeds = depsTreeSeeds(config)

    assertEquals(
      setOf("org.jetbrains.kotlin:kotlin-stdlib"),
      seeds.mainSeeds.keys,
      "only the auto-injected stdlib seed should remain",
    )
    assertEquals(
      setOf("org.jetbrains.kotlin:kotlin-test-junit5"),
      seeds.testSeeds.keys,
      "only the auto-injected junit seed should remain",
    )
  }

  @Test
  fun nativeTargetSuppressesAutoInjection() {
    val config =
      testConfig(
        target = "linuxX64",
        dependencies = emptyMap(),
        testDependencies = mapOf("com.example:nativetest" to "1.0"),
        testSources = listOf("test"),
      )

    val seeds = depsTreeSeeds(config)

    assertEquals(mapOf("com.example:nativetest" to "1.0"), seeds.testSeeds)
    assertFalse(
      "org.jetbrains.kotlin:kotlin-test-junit5" in seeds.testSeeds,
      "native target must not carry the JVM-only auto-injection",
    )
  }

  // On a JVM target, `mainSeeds` is never empty even when the user
  // declares no `[dependencies]` — `autoInjectedMainDeps` adds
  // `kotlin-stdlib` so a `kolt new` project compiles via BTA without an
  // explicit stdlib declaration. Native targets keep the old "really
  // empty" shape because konanc bundles stdlib (ADR 0011).
  @Test
  fun nativeWithEmptyMainAndTestProducesEmptySeeds() {
    val config =
      testConfig(
        target = "linuxX64",
        dependencies = emptyMap(),
        testDependencies = emptyMap(),
        testSources = emptyList(),
      )

    val seeds = depsTreeSeeds(config)

    assertTrue(seeds.isEmpty)
  }

  @Test
  fun jvmWithEmptyMainAndTestStillCarriesAutoInjectedStdlib() {
    val config =
      testConfig(
        target = "jvm",
        dependencies = emptyMap(),
        testDependencies = emptyMap(),
        testSources = emptyList(),
      )

    val seeds = depsTreeSeeds(config)

    assertEquals(
      setOf("org.jetbrains.kotlin:kotlin-stdlib"),
      seeds.mainSeeds.keys,
      "JVM main section must carry the auto-injected stdlib seed: ${seeds.mainSeeds}",
    )
    assertTrue(seeds.testSeeds.isEmpty())
    assertFalse(seeds.isEmpty)
  }
}

class ValidateDepsSubcommandTest {

  @Test
  fun validTreeSubcommand() {
    assertTrue(validateDepsSubcommand(listOf("tree")))
  }

  @Test
  fun validAddSubcommand() {
    assertTrue(validateDepsSubcommand(listOf("add", "com.example:lib:1.0")))
  }

  @Test
  fun validInstallSubcommand() {
    assertTrue(validateDepsSubcommand(listOf("install")))
  }

  @Test
  fun validUpdateSubcommand() {
    assertTrue(validateDepsSubcommand(listOf("update")))
  }

  @Test
  fun emptyArgsReturnsInvalid() {
    assertFalse(validateDepsSubcommand(emptyList()))
  }

  @Test
  fun unknownSubcommandReturnsInvalid() {
    assertFalse(validateDepsSubcommand(listOf("list")))
  }

  @Test
  fun treeWithExtraArgsIsValid() {
    assertTrue(validateDepsSubcommand(listOf("tree", "--verbose")))
  }
}
