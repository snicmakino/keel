@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.eprintln
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.PATH_MAX
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.mkdtemp

/**
 * E2E smoke for NO_COLOR / --no-color / stderr-redirect ANSI suppression (tasks.md 8.1,
 * requirements 2.2, 2.3, 2.4, 2.6).
 *
 * Spawns the built kolt.kexe against a tmp project whose kolt.toml is intentionally malformed so
 * the parse path emits a real error diagnostic to stderr. The four cases each redirect stderr to a
 * file and assert the file contains no CSI introducer (ESC + open bracket) byte sequence.
 *
 * Gated behind KOLT_NOCOLOR_E2E=1 because the realistic scenarios depend on `kolt build` having
 * produced build/debug/kolt.kexe. Without the env variable each case returns immediately so `kolt
 * test` stays fast and offline-safe; with the env variable the test exercises real fork+execvp of
 * the binary and asserts on its stderr file contents.
 *
 * A malformed kolt.toml (rather than a broken *.kt) is used as the synthetic fail fixture: parse
 * failure happens before toolchain resolution or dependency download, so the test is instant and
 * network-free, and the stderr it produces is rendered by kolt's own eprintDiagnostic — which is
 * the exact channel that --no-color / NO_COLOR are supposed to suppress.
 *
 * Shell harness scripts use a Kotlin-side D = "$" token for shell variable references so the
 * raw-string templates do not collide with Kotlin's own `$variable` interpolation.
 */
class NoColorE2EIT {

  private val createdDirs = mutableListOf<String>()

  @AfterTest
  fun cleanup() {
    for (dir in createdDirs) {
      if (fileExists(dir)) {
        removeDirectoryRecursive(dir)
      }
    }
    createdDirs.clear()
  }

  // R2.4: explicit --no-color flag suppresses ANSI in stderr regardless of
  // TTY state. The harness pipes stderr to a file so the destination is also
  // non-TTY, which would already disable color via Auto; the assertion still
  // holds for the contract under test.
  @Test
  fun noColorFlagSuppressesAnsiInStderr() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createFailingFixture("kolt-it-nocolor-flag-")
    val script =
      """
            set -u
            cd "$fixture"
            "$kolt" --no-color build > out.stdout 2> out.stderr
            echo $D? > out.exit
            """
        .trimIndent()
    runHarness(script)
    val exit = readExit(fixture, "out.exit")
    val stderr = readOptional(fixture, "out.stderr") ?: ""
    assertNotEquals(0, exit, "build must fail on malformed kolt.toml; stderr=$stderr")
    assertTrue(stderr.isNotEmpty(), "stderr must contain the error diagnostic; got empty")
    assertNoAnsi(stderr, "--no-color flag")
  }

  // R2.3: NO_COLOR=1 env var suppresses ANSI in stderr. no-color.org spec —
  // any non-empty value disables color.
  @Test
  fun noColorEnvVarSuppressesAnsiInStderr() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createFailingFixture("kolt-it-nocolor-env-")
    val script =
      """
            set -u
            cd "$fixture"
            NO_COLOR=1 "$kolt" build > out.stdout 2> out.stderr
            echo $D? > out.exit
            """
        .trimIndent()
    runHarness(script)
    val exit = readExit(fixture, "out.exit")
    val stderr = readOptional(fixture, "out.stderr") ?: ""
    assertNotEquals(0, exit, "build must fail on malformed kolt.toml; stderr=$stderr")
    assertTrue(stderr.isNotEmpty(), "stderr must contain the error diagnostic; got empty")
    assertNoAnsi(stderr, "NO_COLOR=1 env")
  }

  // R2.2: when stderr is redirected to a file the destination is non-TTY, so
  // the ColorPolicy.Auto branch must report isStderrTty = false and emit no
  // ANSI even without any explicit opt-out flag or env var.
  @Test
  fun stderrRedirectSuppressesAnsi() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createFailingFixture("kolt-it-nocolor-redir-")
    // Explicitly unset NO_COLOR so this case isolates the TTY-detection
    // branch (R2.2) from the env-var branch (R2.3). --no-color is also not
    // passed.
    val script =
      """
            set -u
            unset NO_COLOR
            cd "$fixture"
            "$kolt" build > out.stdout 2> log.txt
            echo $D? > out.exit
            """
        .trimIndent()
    runHarness(script)
    val exit = readExit(fixture, "out.exit")
    val log = readOptional(fixture, "log.txt") ?: ""
    assertNotEquals(0, exit, "build must fail on malformed kolt.toml; log=$log")
    assertTrue(log.isNotEmpty(), "log.txt must contain the error diagnostic; got empty")
    assertNoAnsi(log, "stderr redirect to log.txt")
  }

  // R2.6 (no-color.org spec): NO_COLOR with a non-empty value triggers
  // suppression. Asserting the empty-value branch ("ANSI MAY appear") is
  // unstable in a non-TTY harness, so we assert only the binding contract:
  // any non-empty NO_COLOR value disables color.
  @Test
  fun noColorEnvVarWithArbitraryNonEmptyValueSuppressesAnsi() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createFailingFixture("kolt-it-nocolor-foobar-")
    val script =
      """
            set -u
            cd "$fixture"
            NO_COLOR=foobar "$kolt" build > out.stdout 2> out.stderr
            echo $D? > out.exit
            """
        .trimIndent()
    runHarness(script)
    val exit = readExit(fixture, "out.exit")
    val stderr = readOptional(fixture, "out.stderr") ?: ""
    assertNotEquals(0, exit, "build must fail on malformed kolt.toml; stderr=$stderr")
    assertTrue(stderr.isNotEmpty(), "stderr must contain the error diagnostic; got empty")
    assertNoAnsi(stderr, "NO_COLOR=foobar env")
  }

  // CSI sequences begin with ESC followed by an open bracket (0x1B 0x5B).
  // Asserting on the two-byte introducer catches every ANSI color escape
  // because every ANSI color code is a CSI sequence; checking the
  // introducer is sufficient and avoids false positives from substrings
  // like a literal "[INFO]" tag.
  private fun assertNoAnsi(body: String, label: String) {
    assertTrue(
      !body.contains(ESC_BRACKET),
      "$label: stderr must not contain ANSI escape sequence; got:\n$body",
    )
  }

  // The IT cases need a kolt-built kolt.kexe. `kolt test` does not produce
  // the executable on its own, so we cannot assume the binary is present.
  // When it is missing we surface a clear error rather than silently passing
  // — the env-gate already guarded the "do not run" path; if the user opted
  // in, the binary really should be there.
  private fun locateKoltKexe(): String? {
    val cwd = currentWorkingDir() ?: return null
    val candidates = listOf("$cwd/build/debug/kolt.kexe", "$cwd/build/release/kolt.kexe")
    val found = candidates.firstOrNull { fileExists(it) }
    if (found == null) {
      error(
        "KOLT_NOCOLOR_E2E=1 but kolt.kexe is not built. Run " +
          "`kolt build` first. Looked under: $candidates"
      )
    }
    return found
  }

  // Synthetic fail fixture: the kolt.toml is intentionally malformed so the
  // ktoml decoder rejects it on the very first pass — before toolchain
  // resolution or dependency download. This keeps the test offline and
  // instant while still exercising kolt's own error diagnostic path,
  // which is the channel the color policy is supposed to suppress.
  private fun createFailingFixture(prefix: String): String {
    val dir = createTempDir(prefix)
    writeFileAsString("$dir/kolt.toml", FAIL_FIXTURE_TOML).getOrElse {
      error("write kolt.toml: $it")
    }
    return dir
  }

  private fun createTempDir(prefix: String): String {
    val template = "/tmp/${prefix}XXXXXX"
    val buf = template.encodeToByteArray().copyOf(template.length + 1)
    buf.usePinned { pinned ->
      val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
      val path = result.toKString()
      createdDirs.add(path)
      return path
    }
  }

  private fun currentWorkingDir(): String? = memScoped {
    val buf = allocArray<ByteVar>(PATH_MAX)
    getcwd(buf, PATH_MAX.toULong())?.toKString()
  }

  private fun runHarness(script: String) {
    executeCommand(listOf("bash", "-c", script)).getOrElse { err ->
      error("harness bash failed: $err — script was:\n$script")
    }
  }

  private fun readExit(dir: String, name: String): Int {
    val raw =
      readFileAsString("$dir/$name").getOrElse {
        error("missing $dir/$name — harness did not record an exit code")
      }
    return raw.trim().toIntOrNull() ?: error("could not parse exit code from $dir/$name: '$raw'")
  }

  private fun readOptional(dir: String, name: String): String? {
    val path = "$dir/$name"
    if (!fileExists(path)) return null
    return readFileAsString(path).getOrElse {
      return null
    }
  }

  private fun enabled(): Boolean {
    val on = getenv("KOLT_NOCOLOR_E2E")?.toKString() == "1"
    if (!on && !skipNoticePrinted) {
      skipNoticePrinted = true
      eprintln("NoColorE2EIT: skipped (set KOLT_NOCOLOR_E2E=1 and run `kolt build` to enable)")
    }
    return on
  }

  companion object {
    private const val D = "$"

    private var skipNoticePrinted = false

    // Two-byte CSI introducer: ESC (0x1B) followed by open bracket. Built
    // via the Unicode escape so the source file contains no literal ESC
    // byte (which would otherwise mangle terminal output if anyone cats
    // the file or grep -r over the test tree).
    private const val ESC_BRACKET = "\u001B["

    // Malformed: dangling "[" opens a table header that is never closed by
    // a "]", which the ktoml lexer rejects deterministically with a parse
    // error. No [dependencies] section means no network is touched even
    // if the parser were lenient. No source files exist either, so the
    // build would fail at config-load anyway.
    private val FAIL_FIXTURE_TOML =
      """
            name = "nocolor-it"
            version = "0.0.1"
            kind = "app"

            [kotlin
            version = "2.3.20"
            """
        .trimIndent()
  }
}
