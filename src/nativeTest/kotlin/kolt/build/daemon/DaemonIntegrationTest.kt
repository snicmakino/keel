package kolt.build.daemon

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.build.CompileRequest
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.eprintln
import kolt.infra.fileExists
import kolt.infra.listJarFiles
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * End-to-end round-trip against a real `kolt-compiler-daemon` fat
 * jar, a real bootstrap JDK, and a real kotlinc `lib/` directory.
 *
 * ## Opt-in via `KOLT_DAEMON_IT=1`
 *
 * This test is disabled by default — running it requires external
 * artefacts (JVM + kolt-compiler-daemon-all.jar + the jars in kotlinc/lib)
 * that a clean CI environment does not have. Set `KOLT_DAEMON_IT=1`
 * and supply the two env vars described below to run it locally.
 * Without `KOLT_DAEMON_IT=1` the test returns immediately and is
 * reported as passing, which keeps `./gradlew linuxX64Test` green on
 * any developer machine without ceremony.
 *
 * ## Environment variables
 *
 * - `KOLT_DAEMON_IT=1` — master switch.
 * - `JAVA_HOME` — pointed at any JDK new enough to run the Kotlin
 *   compiler (matches the bootstrap constant, but this test does not
 *   assume `~/.kolt/toolchains/jdk/<BOOTSTRAP_JDK_VERSION>` exists).
 * - `KOLT_IT_COMPILER_JARS_DIR` — absolute path to a kotlinc `lib/`
 *   directory containing `kotlin-compiler.jar` and friends. The test
 *   globs every .jar under it and feeds the list to the daemon via
 *   `--compiler-jars`.
 *
 * The daemon fat jar is resolved with the production
 * [resolveDaemonJar] helper so a locally-built
 * `kolt-compiler-daemon-all.jar` from `./gradlew :kolt-compiler-daemon:shadowJar`
 * is picked up automatically via the dev-fallback branch.
 *
 * ## What is verified
 *
 * 1. Cold compile of a trivial top-level function succeeds — this
 *    exercises the whole spawn -> socket retry -> FrameCodec
 *    round-trip -> SharedCompilerHost path on the JVM side.
 * 2. Warm compile (second `compile()` call against the same backend
 *    instance, therefore the same daemon process) also succeeds.
 *    This confirms the daemon stays alive between compiles and the
 *    connector's optimistic fast-path connects the already-running
 *    server instead of re-spawning.
 *
 * Cleanup leaves the daemon process running; it exits on its own idle
 * timeout. The `/tmp/kolt_daemon_it_<pid>/` state directory is torn
 * down in `finally` so a fresh run produces a fresh socket path.
 *
 * ## Observed host-level failure modes
 *
 * One of the open questions from PR3 S6 was whether
 * `DaemonCompilerBackend.mapReplyToOutcome` needs to sniff
 * `exitCode==2 + empty diagnostics` as a "host-level protocol error"
 * and reroute it to `BackendUnavailable`. Running this test against
 * the current JVM side produces `exitCode==0` on success and normal
 * `CompilationFailed(exitCode=1, …)` on a deliberately-broken source
 * — the `writeProtocolError` path only fires on malformed wire
 * traffic, which this test cannot provoke without replacing the
 * FrameCodec. The sniff is therefore left as a TODO until a real
 * reproduction appears in the field.
 */
@OptIn(ExperimentalForeignApi::class)
class DaemonIntegrationTest {

    @Test
    fun realDaemonCompilesTrivialSourceTwice() {
        if (!integrationTestsEnabled()) return
        val env = requireEnv() ?: return

        val projectDir = "/tmp/kolt_daemon_it_${getpid()}"
        val stateDir = "$projectDir/state"
        val outputDir = "$projectDir/out"
        val srcFile = "$projectDir/Hello.kt"
        try {
            ensureDirectoryRecursive(stateDir)
            ensureDirectoryRecursive(outputDir)
            writeFileAsString(srcFile, "fun hello(): Int = 42\n")

            val backend = DaemonCompilerBackend(
                javaBin = env.javaBin,
                daemonJarPath = env.daemonJarPath,
                compilerJars = env.compilerJars,
                socketPath = "$stateDir/daemon.sock",
                logPath = "$stateDir/daemon.log",
            )

            val request = CompileRequest(
                workingDir = projectDir,
                classpath = emptyList(),
                sources = listOf(srcFile),
                outputPath = outputDir,
                moduleName = "kolt_daemon_it",
            )

            val cold = backend.compile(request)
            assertNotNull(
                cold.get(),
                "cold compile failed: ${cold.getError()} (daemon log: $stateDir/daemon.log)",
            )

            val warm = backend.compile(request)
            assertNotNull(
                warm.get(),
                "warm compile failed: ${warm.getError()} (daemon log: $stateDir/daemon.log)",
            )
        } finally {
            removeDirectoryRecursive(projectDir)
        }
    }

    private data class ItEnv(
        val javaBin: String,
        val daemonJarPath: String,
        val compilerJars: List<String>,
    )

    private fun requireEnv(): ItEnv? {
        val javaHome = getenv("JAVA_HOME")?.toKString()
        if (javaHome.isNullOrEmpty()) {
            eprintln("DaemonIntegrationTest: JAVA_HOME not set, skipping")
            return null
        }
        val javaBin = "$javaHome/bin/java"
        if (!fileExists(javaBin)) {
            eprintln("DaemonIntegrationTest: $javaBin missing, skipping")
            return null
        }

        val daemonJar = when (val r = resolveDaemonJar()) {
            is DaemonJarResolution.Resolved -> r.path
            DaemonJarResolution.NotFound -> {
                eprintln("DaemonIntegrationTest: daemon jar not found (build it with './gradlew :kolt-compiler-daemon:shadowJar'), skipping")
                return null
            }
        }

        val libDir = getenv("KOLT_IT_COMPILER_JARS_DIR")?.toKString()
        if (libDir.isNullOrEmpty()) {
            eprintln("DaemonIntegrationTest: KOLT_IT_COMPILER_JARS_DIR env var required, skipping")
            return null
        }
        val jars = listJarFiles(libDir).getOrElse { null }
        if (jars.isNullOrEmpty()) {
            eprintln("DaemonIntegrationTest: no jars in $libDir, skipping")
            return null
        }

        return ItEnv(javaBin, daemonJar, jars)
    }

    private fun integrationTestsEnabled(): Boolean =
        getenv("KOLT_DAEMON_IT")?.toKString() == "1"
}
