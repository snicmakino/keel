@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.eprintln
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kolt.infra.removeDirectoryRecursive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * E2E pin for `kolt info --format=json` (tasks.md 8.4, requirements 7.1, 7.2).
 *
 * The structured output channel must remain ANSI-free regardless of color policy. The
 * DiagnosticWriter is stderr-only by contract, so the stdout JSON path should never carry CSI
 * introducer bytes; this IT pins that contract end-to-end against the built kolt.kexe.
 *
 * Scenarios:
 * 1. Default invocation (`kolt info --format=json`) with NO_COLOR cleared — stdout is JSON-shaped,
 *    no ANSI, exit 0.
 * 2. `--no-color` flag — same assertion; the flag must not perturb the JSON channel.
 * 3. `NO_COLOR=1` env var — same assertion.
 * 4. Byte-equivalence: scenarios 1 / 2 / 3 produce byte-identical stdout, pinning the contract that
 *    the JSON channel is invariant under color policy.
 *
 * Gated behind `KOLT_INFO_JSON_E2E=1` because the cases require a built `build/debug/kolt.kexe` (or
 * `build/release/kolt.kexe`); without the env var the methods return immediately so `kolt test`
 * stays fast.
 *
 * Shell harness scripts use a Kotlin-side `D = "$"` token for shell variable references so the
 * raw-string templates do not collide with Kotlin's own `$variable` interpolation.
 */
class InfoJsonAnsiE2EIT {

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

  // R7.1: default invocation must not inject ANSI into the JSON channel. NO_COLOR is explicitly
  // cleared so this case isolates the stdout-channel contract from the env-var branch — even with
  // no opt-out signal, the JSON path is stderr-detached and color-free.
  @Test
  fun defaultInvocationProducesAnsiFreeJson() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val workdir = createTempDir("kolt-it-info-json-default-")
    val script =
      """
            set -u
            unset NO_COLOR
            cd "$workdir"
            "$kolt" info --format=json > out.stdout 2> out.stderr
            echo $D? > out.exit
            """
        .trimIndent()
    runHarness(script)
    val exit = readExit(workdir, "out.exit")
    val stdout = readOptional(workdir, "out.stdout") ?: ""
    val stderr = readOptional(workdir, "out.stderr") ?: ""
    assertEquals(0, exit, "info --format=json must exit 0; stderr=$stderr")
    assertTrue(stdout.isNotEmpty(), "stdout must contain JSON; got empty")
    assertJsonShaped(stdout, "default invocation")
    assertNoAnsi(stdout, "default invocation stdout")
  }

  // R7.1 + R2.4: --no-color flag must not perturb stdout JSON. The flag flips kolt's ColorPolicy
  // to Never; the JSON channel must remain byte-identical to the default case.
  @Test
  fun noColorFlagDoesNotPerturbJson() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val workdir = createTempDir("kolt-it-info-json-flag-")
    val script =
      """
            set -u
            unset NO_COLOR
            cd "$workdir"
            "$kolt" --no-color info --format=json > out.stdout 2> out.stderr
            echo $D? > out.exit
            """
        .trimIndent()
    runHarness(script)
    val exit = readExit(workdir, "out.exit")
    val stdout = readOptional(workdir, "out.stdout") ?: ""
    val stderr = readOptional(workdir, "out.stderr") ?: ""
    assertEquals(0, exit, "info --format=json with --no-color must exit 0; stderr=$stderr")
    assertTrue(stdout.isNotEmpty(), "stdout must contain JSON; got empty")
    assertJsonShaped(stdout, "--no-color flag")
    assertNoAnsi(stdout, "--no-color flag stdout")
  }

  // R7.1 + R2.3: NO_COLOR=1 env var must not perturb stdout JSON. Same channel-isolation contract
  // as the flag case but via the env-var branch.
  @Test
  fun noColorEnvDoesNotPerturbJson() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val workdir = createTempDir("kolt-it-info-json-env-")
    val script =
      """
            set -u
            cd "$workdir"
            NO_COLOR=1 "$kolt" info --format=json > out.stdout 2> out.stderr
            echo $D? > out.exit
            """
        .trimIndent()
    runHarness(script)
    val exit = readExit(workdir, "out.exit")
    val stdout = readOptional(workdir, "out.stdout") ?: ""
    val stderr = readOptional(workdir, "out.stderr") ?: ""
    assertEquals(0, exit, "info --format=json with NO_COLOR=1 must exit 0; stderr=$stderr")
    assertTrue(stdout.isNotEmpty(), "stdout must contain JSON; got empty")
    assertJsonShaped(stdout, "NO_COLOR=1 env")
    assertNoAnsi(stdout, "NO_COLOR=1 env stdout")
  }

  // R7.1 invariance pin: the stdout JSON channel is byte-identical across the three color-policy
  // shapes (default / --no-color flag / NO_COLOR=1 env). If any future change leaks color decisions
  // into the JSON channel, this assertion fires before downstream tools see the divergence.
  @Test
  fun jsonOutputIsByteIdenticalAcrossColorPolicies() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val workdir = createTempDir("kolt-it-info-json-invariance-")
    val script =
      """
            set -u
            cd "$workdir"
            unset NO_COLOR
            "$kolt" info --format=json > default.stdout 2> default.stderr
            echo $D? > default.exit
            "$kolt" --no-color info --format=json > flag.stdout 2> flag.stderr
            echo $D? > flag.exit
            NO_COLOR=1 "$kolt" info --format=json > env.stdout 2> env.stderr
            echo $D? > env.exit
            """
        .trimIndent()
    runHarness(script)
    assertEquals(0, readExit(workdir, "default.exit"), "default invocation must exit 0")
    assertEquals(0, readExit(workdir, "flag.exit"), "--no-color invocation must exit 0")
    assertEquals(0, readExit(workdir, "env.exit"), "NO_COLOR=1 invocation must exit 0")
    val default = readOptional(workdir, "default.stdout") ?: ""
    val flag = readOptional(workdir, "flag.stdout") ?: ""
    val env = readOptional(workdir, "env.stdout") ?: ""
    assertEquals(
      default,
      flag,
      "stdout JSON must be byte-identical between default and --no-color invocations",
    )
    assertEquals(
      default,
      env,
      "stdout JSON must be byte-identical between default and NO_COLOR=1 invocations",
    )
  }

  // CSI sequences begin with ESC followed by an open bracket (0x1B 0x5B). Asserting on the two-byte
  // introducer catches every ANSI color escape because every ANSI color code is a CSI sequence;
  // checking the introducer is sufficient and avoids false positives from substrings like a literal
  // "[" inside a JSON array — though `kolt info --format=json` does not currently emit one, the
  // schema permits arrays (e.g., `enabledPlugins`) so the assertion targets the ESC byte
  // explicitly.
  private fun assertNoAnsi(body: String, label: String) {
    assertTrue(
      !body.contains(ESC_BRACKET),
      "$label: stdout must not contain ANSI escape sequence; got:\n$body",
    )
  }

  // Pragmatic JSON-shape check. Kotlin/Native's test harness has no JSON parser bundled; verifying
  // the payload starts with "{" and trim-ends with "}" is sufficient to assert the writer emitted
  // a JSON object (not an empty payload, not stderr noise leaked to stdout).
  private fun assertJsonShaped(body: String, label: String) {
    val trimmed = body.trimEnd()
    assertTrue(
      trimmed.startsWith("{") && trimmed.endsWith("}"),
      "$label: stdout must be a JSON object; got:\n$body",
    )
  }

  // The IT cases need a kolt-built kolt.kexe. `kolt test` does not produce the executable on its
  // own; if the user opted into the gate the binary must already be present.
  private fun locateKoltKexe(): String? {
    val cwd = currentWorkingDir() ?: return null
    val candidates = listOf("$cwd/build/debug/kolt.kexe", "$cwd/build/release/kolt.kexe")
    val found = candidates.firstOrNull { fileExists(it) }
    if (found == null) {
      error(
        "KOLT_INFO_JSON_E2E=1 but kolt.kexe is not built. Run " +
          "`kolt build` first. Looked under: $candidates"
      )
    }
    return found
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
    val on = getenv("KOLT_INFO_JSON_E2E")?.toKString() == "1"
    if (!on && !skipNoticePrinted) {
      skipNoticePrinted = true
      eprintln(
        "InfoJsonAnsiE2EIT: skipped (set KOLT_INFO_JSON_E2E=1 and run `kolt build` to enable)"
      )
    }
    return on
  }

  companion object {
    private const val D = "$"

    private var skipNoticePrinted = false

    // Two-byte CSI introducer: ESC (0x1B) followed by open bracket. Built via the Unicode escape
    // so the source file contains no literal ESC byte (which would otherwise mangle terminal
    // output if anyone cats the file or grep -r over the test tree).
    private const val ESC_BRACKET = "\u001B["
  }
}
