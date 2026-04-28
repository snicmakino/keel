package kolt.cli

import kolt.build.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParseKoltArgsTest {

  @Test
  fun absentReleaseFlagYieldsDebugProfile() {
    val parsed = parseKoltArgs(listOf("build"))

    assertEquals(Profile.Debug, parsed.profile)
    assertEquals(listOf("build"), parsed.filteredArgs)
  }

  @Test
  fun presentReleaseFlagYieldsReleaseProfile() {
    val parsed = parseKoltArgs(listOf("--release", "build"))

    assertEquals(Profile.Release, parsed.profile)
  }

  @Test
  fun releaseFlagIsStrippedFromFilteredArgs() {
    val parsed = parseKoltArgs(listOf("--release", "build"))

    assertFalse(
      parsed.filteredArgs.contains("--release"),
      "filteredArgs must not leak --release into subcommand parsing: ${parsed.filteredArgs}",
    )
    assertEquals(listOf("build"), parsed.filteredArgs)
  }

  @Test
  fun releaseCombinesWithOtherKoltLevelFlags() {
    val parsed = parseKoltArgs(listOf("--no-daemon", "--release", "--watch", "test"))

    assertEquals(Profile.Release, parsed.profile)
    assertFalse(parsed.useDaemon)
    assertTrue(parsed.watch)
    assertEquals(listOf("test"), parsed.filteredArgs)
  }

  @Test
  fun releaseAfterDoubleDashIsTreatedAsPassthrough() {
    val parsed = parseKoltArgs(listOf("run", "--", "--release"))

    assertEquals(Profile.Debug, parsed.profile)
    assertEquals(listOf("run", "--", "--release"), parsed.filteredArgs)
  }

  @Test
  fun releaseFlagPositionDoesNotMatter() {
    val before = parseKoltArgs(listOf("--release", "build"))
    val after = parseKoltArgs(listOf("build", "--release"))

    assertEquals(before.profile, after.profile)
    assertEquals(before.filteredArgs, after.filteredArgs)
  }
}
