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
 * E2E pin for `kotlinc` / `konanc` honoring `NO_COLOR=1` (tasks.md 8.3, requirements 6.2, 6.3).
 *
 * Where 8.1 (`NoColorE2EIT`) exercises kolt's own diagnostic channel via a malformed `kolt.toml`,
 * this IT exercises the forwarded subprocess stderr channel: the fixture is a syntactically broken
 * `*.kt` that reaches `kotlinc` (and on scenario 4, `konanc`). The contract under test is that when
 * `colorPolicy` is disabled, kolt sets `NO_COLOR=1` in the subprocess env (R6.3) and that the
 * upstream Kotlin compilers honor that env — i.e. emit no `\x1B[` CSI introducer to their stderr.
 * Validate-design Issue 3's mitigation is to lock that honor as a test contract; if a future Kotlin
 * / konanc bump regresses, this IT goes RED, prompting either a version pin or a post-strip
 * fallback (Risks table).
 *
 * Gated behind `KOLT_SUBPROC_COLOR_E2E=1`. The env gate is the user's signal that the host can
 * actually compile via kolt — including, for scenario 4, having konanc downloaded (kolt fetches it
 * on first native build under `~/.kolt/`). Without the env variable each case returns immediately
 * so `kolt test` stays fast and offline-safe.
 *
 * Scenarios:
 * 1. `NO_COLOR=1 kolt --no-daemon build` against a JVM fixture — kotlinc direct subprocess.
 * 2. `kolt --no-color --no-daemon build` against a JVM fixture — same path, flag mechanism.
 * 3. `NO_COLOR=1 kolt build` against a JVM fixture — JVM daemon path (default).
 * 4. `NO_COLOR=1 kolt build` against a native (linuxX64) fixture — konanc / native daemon.
 *
 * Each scenario asserts the build fails (compile error), produces non-empty stderr, and that the
 * stderr contains no `\x1B[` byte sequence.
 *
 * Shell harness scripts use a Kotlin-side `D = "$"` token for shell variable references so the
 * raw-string templates do not collide with Kotlin's own `$variable` interpolation.
 */
class SubprocessColorForwardingE2EIT {

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

  // Scenario 1 (R6.3 direct subprocess path). NO_COLOR env var threads through
  // SubprocessCompilerBackend's env injection into the kotlinc child process.
  @Test
  fun kotlincDirectSubprocessHonorsNoColorEnv() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createJvmCompileErrorFixture("kolt-it-subproc-direct-env-")
    val script =
      """
            set -u
            cd "$fixture"
            NO_COLOR=1 "$kolt" --no-daemon build > out.stdout 2> out.stderr
            echo $D? > out.exit
            """
        .trimIndent()
    runHarness(script)
    val exit = readExit(fixture, "out.exit")
    val stderr = readOptional(fixture, "out.stderr") ?: ""
    assertNotEquals(0, exit, "build must fail on broken kotlin source; stderr=$stderr")
    assertTrue(stderr.isNotEmpty(), "stderr must contain the kotlinc diagnostic; got empty")
    assertTrue(
      stderr.contains("Main.kt"),
      "scenario 1: stderr must come from kotlinc (source-file-level diagnostic), " +
        "not kolt's validator; got:\n$stderr",
    )
    assertNoAnsi(stderr, "scenario 1: NO_COLOR=1 kolt --no-daemon build (kotlinc direct)")
  }

  // Scenario 2 (R6.3 direct subprocess path, flag mechanism). The --no-color
  // flag flips the kolt-level ColorPolicy to Never, which the
  // SubprocessCompilerBackend reads and translates into NO_COLOR=1 for the
  // child env. Independent of NO_COLOR being set in the parent shell.
  @Test
  fun kotlincDirectSubprocessHonorsNoColorFlag() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createJvmCompileErrorFixture("kolt-it-subproc-direct-flag-")
    // Explicitly unset NO_COLOR so this case isolates the --no-color flag
    // mechanism from the env-var mechanism (scenario 1).
    val script =
      """
            set -u
            unset NO_COLOR
            cd "$fixture"
            "$kolt" --no-color --no-daemon build > out.stdout 2> out.stderr
            echo $D? > out.exit
            """
        .trimIndent()
    runHarness(script)
    val exit = readExit(fixture, "out.exit")
    val stderr = readOptional(fixture, "out.stderr") ?: ""
    assertNotEquals(0, exit, "build must fail on broken kotlin source; stderr=$stderr")
    assertTrue(stderr.isNotEmpty(), "stderr must contain the kotlinc diagnostic; got empty")
    assertTrue(
      stderr.contains("Main.kt"),
      "scenario 2: stderr must come from kotlinc (source-file-level diagnostic), " +
        "not kolt's validator; got:\n$stderr",
    )
    assertNoAnsi(stderr, "scenario 2: kolt --no-color --no-daemon build (kotlinc direct, flag)")
  }

  // Scenario 3 (R6.3 daemon path). Default invocation routes through the JVM
  // compiler daemon; DaemonCompilerBackend injects NO_COLOR=1 into the daemon
  // launch env when ColorPolicy is disabled. Asserting on the same broken-kt
  // fixture confirms the daemon-launched kotlinc honors NO_COLOR equivalently
  // to the direct subprocess case.
  @Test
  fun kotlincJvmDaemonHonorsNoColorEnv() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createJvmCompileErrorFixture("kolt-it-subproc-daemon-env-")
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
    assertNotEquals(0, exit, "build must fail on broken kotlin source; stderr=$stderr")
    assertTrue(stderr.isNotEmpty(), "stderr must contain the kotlinc diagnostic; got empty")
    assertTrue(
      stderr.contains("Main.kt"),
      "scenario 3: stderr must come from kotlinc (source-file-level diagnostic), " +
        "not kolt's validator; got:\n$stderr",
    )
    assertNoAnsi(stderr, "scenario 3: NO_COLOR=1 kolt build (JVM daemon)")
  }

  // Scenario 4 (R6.3 native daemon path / konanc). The native target routes
  // through NativeDaemonBackend / NativeSubprocessBackend; the konanc child
  // process must honor NO_COLOR=1 with the same contract as kotlinc. The env
  // gate KOLT_SUBPROC_COLOR_E2E=1 implies the user has a working build
  // environment; the first native invocation will download konanc on demand.
  @Test
  fun konancNativeDaemonHonorsNoColorEnv() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createNativeCompileErrorFixture("kolt-it-subproc-native-env-")
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
    assertNotEquals(0, exit, "native build must fail on broken kotlin source; stderr=$stderr")
    assertTrue(stderr.isNotEmpty(), "stderr must contain the konanc diagnostic; got empty")
    assertTrue(
      stderr.contains("Main.kt"),
      "scenario 4: stderr must come from konanc (source-file-level diagnostic), " +
        "not kolt's validator; got:\n$stderr",
    )
    assertNoAnsi(stderr, "scenario 4: NO_COLOR=1 kolt build (konanc / native daemon)")
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
        "KOLT_SUBPROC_COLOR_E2E=1 but kolt.kexe is not built. Run " +
          "`kolt build` first. Looked under: $candidates"
      )
    }
    return found
  }

  // JVM-target fixture: kolt.toml is well-formed, but src/Main.kt contains
  // intentionally broken Kotlin syntax (`let` is not a Kotlin keyword) so
  // kotlinc reaches its parser and emits a real compile-error diagnostic to
  // stderr. That stderr is the channel under test — the one to which the
  // SubprocessCompilerBackend / DaemonCompilerBackend forwards. No
  // [dependencies] section means the compile path runs without network
  // (only the bundled kotlinc + kolt's own toolchain bootstrap).
  private fun createJvmCompileErrorFixture(prefix: String): String {
    val dir = createTempDir(prefix)
    writeFileAsString("$dir/kolt.toml", JVM_FIXTURE_TOML).getOrElse {
      error("write kolt.toml: $it")
    }
    val srcDir = "$dir/src"
    executeCommand(listOf("mkdir", "-p", srcDir)).getOrElse { error("mkdir src: $it") }
    writeFileAsString("$srcDir/Main.kt", BROKEN_MAIN).getOrElse { error("write Main.kt: $it") }
    return dir
  }

  // Native-target fixture: same broken-kt content but with target = "linuxX64"
  // so the build routes through konanc (NativeDaemonBackend or its subprocess
  // fallback) instead of kotlinc-jvm. Using the linuxX64 target string to
  // match the kolt repo's own kolt.toml; kolt downloads konanc on demand.
  private fun createNativeCompileErrorFixture(prefix: String): String {
    val dir = createTempDir(prefix)
    writeFileAsString("$dir/kolt.toml", NATIVE_FIXTURE_TOML).getOrElse {
      error("write kolt.toml: $it")
    }
    val srcDir = "$dir/src"
    executeCommand(listOf("mkdir", "-p", srcDir)).getOrElse { error("mkdir src: $it") }
    writeFileAsString("$srcDir/Main.kt", BROKEN_MAIN).getOrElse { error("write Main.kt: $it") }
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
    val on = getenv("KOLT_SUBPROC_COLOR_E2E")?.toKString() == "1"
    if (!on && !skipNoticePrinted) {
      skipNoticePrinted = true
      eprintln(
        "SubprocessColorForwardingE2EIT: skipped " +
          "(set KOLT_SUBPROC_COLOR_E2E=1 and run `kolt build` to enable)"
      )
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

    private const val JVM_FIXTURE_NAME = "subproc-color-jvm"
    private const val NATIVE_FIXTURE_NAME = "subproc-color-native"

    // `let` is not a Kotlin keyword for declaring locals; it is a stdlib
    // scope function. Used as a statement-leading identifier this is a
    // syntax error the kotlinc parser rejects deterministically with a
    // diagnostic on stderr — which is exactly the forwarded channel that
    // the color-policy contract is supposed to govern.
    private val BROKEN_MAIN =
      """
            fun main() {
                let x = 1
                println(x)
            }
            """
        .trimIndent()

    private val JVM_FIXTURE_TOML =
      """
            name = "$JVM_FIXTURE_NAME"
            version = "0.0.1"
            kind = "app"

            [kotlin]
            version = "2.3.20"

            [build]
            target = "jvm"
            jvm_target = "21"
            main = "main"
            sources = ["src"]
            test_sources = []
            """
        .trimIndent()

    private val NATIVE_FIXTURE_TOML =
      """
            name = "$NATIVE_FIXTURE_NAME"
            version = "0.0.1"
            kind = "app"

            [kotlin]
            version = "2.3.20"

            [build]
            target = "linuxX64"
            main = "main"
            sources = ["src"]
            test_sources = []
            """
        .trimIndent()
  }
}
