package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kolt.build.UserJdkError
import kolt.build.UserJdkHome
import kolt.build.daemon.BOOTSTRAP_JDK_VERSION
import kolt.config.KoltPaths
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceSdkHomePathTest {

  private val paths = KoltPaths(home = "/fake/home")

  @Test
  fun userJdkResolvedReturnsItsHomeWithoutWarning() {
    val warnings = mutableListOf<String>()
    val home =
      resolveWorkspaceSdkHomePath(
        testConfig(),
        paths,
        resolveUserJdk = { _, _ -> Ok(UserJdkHome(version = "21", home = "/usr/lib/jvm/java-21")) },
        resolveBootstrap = { error("must not consult bootstrap when user JDK resolves") },
        warningSink = { warnings += it },
      )

    assertEquals("/usr/lib/jvm/java-21", home)
    assertTrue(warnings.isEmpty(), "expected no warnings, got $warnings")
  }

  @Test
  fun managedMissingEmitsTargetedWarningAndReturnsNull() {
    val warnings = mutableListOf<String>()
    val home =
      resolveWorkspaceSdkHomePath(
        testConfig(jdk = "21"),
        paths,
        resolveUserJdk = { _, _ ->
          Err(UserJdkError.ManagedMissing(version = "21", expectedPath = paths.jdkPath("21")))
        },
        resolveBootstrap = { error("must not consult bootstrap when user pin is missing") },
        warningSink = { warnings += it },
      )

    assertNull(home)
    assertEquals(1, warnings.size)
    val msg = warnings.single()
    assertTrue(msg.contains("jdk 21 not installed"), "missing version label in: $msg")
    assertTrue(msg.contains("kolt toolchain install"), "missing action item in: $msg")
  }

  // #356: PATH has no `java`, but kolt-managed bootstrap JDK drives the build.
  // Use the bootstrap home as the IDE's class-root anchor and stay silent.
  @Test
  fun systemProbeFailedFallsBackToBootstrapHomeSilentlyWhenInstalled() {
    val warnings = mutableListOf<String>()
    val home =
      resolveWorkspaceSdkHomePath(
        testConfig(),
        paths,
        resolveUserJdk = { _, _ -> Err(UserJdkError.SystemProbeFailed) },
        resolveBootstrap = { p -> p.javaBin(BOOTSTRAP_JDK_VERSION) },
        warningSink = { warnings += it },
      )

    assertEquals(paths.jdkPath(BOOTSTRAP_JDK_VERSION), home)
    assertTrue(warnings.isEmpty(), "expected no warnings, got $warnings")
  }

  // #356: pre-first-build state — bootstrap not yet provisioned. Stay silent;
  // next build provisions bootstrap and re-writes workspace.json.
  @Test
  fun systemProbeFailedReturnsNullSilentlyWhenBootstrapMissing() {
    val warnings = mutableListOf<String>()
    val home =
      resolveWorkspaceSdkHomePath(
        testConfig(),
        paths,
        resolveUserJdk = { _, _ -> Err(UserJdkError.SystemProbeFailed) },
        resolveBootstrap = { null },
        warningSink = { warnings += it },
      )

    assertNull(home)
    assertTrue(warnings.isEmpty(), "expected no warnings, got $warnings")
  }
}
