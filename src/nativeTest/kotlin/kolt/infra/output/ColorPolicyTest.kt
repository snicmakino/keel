package kolt.infra.output

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorPolicyTest {
  @AfterTest
  fun resetGlobal() {
    ColorPolicy.install(ColorPolicy.Never)
  }

  @Test
  fun noColorFlagYieldsNever() {
    val policy = resolveColorPolicy(noColorFlag = true, getEnv = { null }, isTty = { _ -> true })
    assertEquals(ColorPolicy.Never, policy)
  }

  @Test
  fun noColorEnvYieldsNever() {
    val policy =
      resolveColorPolicy(
        noColorFlag = false,
        getEnv = { if (it == "NO_COLOR") "1" else null },
        isTty = { _ -> true },
      )
    assertEquals(ColorPolicy.Never, policy)
  }

  @Test
  fun emptyNoColorEnvIsIgnored() {
    // no-color.org spec: NO_COLOR must be set to a non-empty string to take effect.
    val policy =
      resolveColorPolicy(
        noColorFlag = false,
        getEnv = { if (it == "NO_COLOR") "" else null },
        isTty = { _ -> true },
      )
    assertEquals(ColorPolicy.Auto(isStderrTty = true, isStdoutTty = true), policy)
  }

  @Test
  fun bothFlagAndEnvYieldNever() {
    val policy =
      resolveColorPolicy(
        noColorFlag = true,
        getEnv = { if (it == "NO_COLOR") "1" else null },
        isTty = { _ -> true },
      )
    assertEquals(ColorPolicy.Never, policy)
  }

  @Test
  fun autoReflectsPerStreamTtyState() {
    val policy =
      resolveColorPolicy(
        noColorFlag = false,
        getEnv = { null },
        // STDERR_FILENO=2, STDOUT_FILENO=1
        isTty = { fd -> fd == 2 },
      )
    assertEquals(ColorPolicy.Auto(isStderrTty = true, isStdoutTty = false), policy)
  }

  @Test
  fun shouldColorAlwaysReturnsTrue() {
    assertTrue(ColorPolicy.Always.shouldColor(Stream.Stderr))
    assertTrue(ColorPolicy.Always.shouldColor(Stream.Stdout))
  }

  @Test
  fun shouldColorNeverReturnsFalse() {
    assertEquals(false, ColorPolicy.Never.shouldColor(Stream.Stderr))
    assertEquals(false, ColorPolicy.Never.shouldColor(Stream.Stdout))
  }

  @Test
  fun shouldColorAutoUsesPerStreamTty() {
    val policy = ColorPolicy.Auto(isStderrTty = true, isStdoutTty = false)
    assertTrue(policy.shouldColor(Stream.Stderr))
    assertEquals(false, policy.shouldColor(Stream.Stdout))
  }

  @Test
  fun installAndCurrentRoundTrip() {
    ColorPolicy.install(ColorPolicy.Always)
    assertEquals(ColorPolicy.Always, ColorPolicy.current())
    ColorPolicy.install(ColorPolicy.Never)
    assertEquals(ColorPolicy.Never, ColorPolicy.current())
  }
}
