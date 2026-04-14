package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.infra.listJarFiles

// Everything `DaemonCompilerBackend` needs to try to talk to a warm
// daemon. Collected into one value so doBuild() can decide in a single
// branch whether the daemon path is reachable for this build.
internal data class DaemonSetup(
    val javaBin: String,
    val daemonJarPath: String,
    val compilerJars: List<String>,
    val socketPath: String,
    val logPath: String,
)

// Reasons the daemon path is not reachable for this build. Each
// variant maps to a one-line warning the user sees once, after which
// the build falls back to the subprocess compile path. None of these
// are build failures — ADR 0016 §5 guarantees the daemon is never
// load-bearing for correctness.
internal sealed interface DaemonPreconditionError {
    // The bootstrap JDK is not yet installed under
    // ~/.kolt/toolchains/jdk/<BOOTSTRAP_JDK_VERSION>. Fix: the user
    // runs `kolt install jdk <version>` once. See ADR 0017.
    data object BootstrapJdkMissing : DaemonPreconditionError

    // The `kolt-compiler-daemon-all.jar` was not found in any of the
    // locations [resolveDaemonJar] probes (env override, libexec
    // co-location, dev fallback). Not a user misconfig: it means
    // kolt itself was not installed with a daemon jar beside it.
    data object DaemonJarMissing : DaemonPreconditionError

    // The kotlinc lib directory either does not exist or contains no
    // .jar files, so there is nothing to load into the daemon's
    // compiler classloader. Includes the directory so the warning can
    // point at the exact path the user needs to repair.
    data class CompilerJarsMissing(val kotlincLibDir: String) : DaemonPreconditionError
}

/**
 * Collects the inputs [DaemonCompilerBackend] needs and hands back
 * either a complete [DaemonSetup] or the first missing piece. This is
 * the single point where the daemon path is either "available" or
 * "not available for this build" — doBuild() branches on the result
 * and picks [kolt.build.FallbackCompilerBackend] or a plain
 * [kolt.build.SubprocessCompilerBackend] accordingly.
 *
 * Seams ([resolveJavaBin], [resolveDaemonJar], [listCompilerJars]) are
 * parameterised so unit tests can drive every variant of
 * [DaemonPreconditionError] without touching the real filesystem.
 * Defaults wire up the production helpers; production callers pass
 * only [paths], [kotlincVersion], and [absProjectPath].
 */
internal fun resolveDaemonPreconditions(
    paths: KoltPaths,
    kotlincVersion: String,
    absProjectPath: String,
    resolveJavaBin: (KoltPaths) -> String? = ::resolveBootstrapJavaBin,
    resolveDaemonJar: () -> DaemonJarResolution = ::resolveDaemonJar,
    listCompilerJars: (String) -> List<String>? = { dir -> listJarFiles(dir).getOrElse { null } },
): Result<DaemonSetup, DaemonPreconditionError> {
    val javaBin = resolveJavaBin(paths)
        ?: return Err(DaemonPreconditionError.BootstrapJdkMissing)

    val daemonJar = when (val res = resolveDaemonJar()) {
        is DaemonJarResolution.Resolved -> res.path
        DaemonJarResolution.NotFound -> return Err(DaemonPreconditionError.DaemonJarMissing)
    }

    val kotlincLibDir = "${paths.kotlincPath(kotlincVersion)}/lib"
    val compilerJars = listCompilerJars(kotlincLibDir)
    if (compilerJars.isNullOrEmpty()) {
        return Err(DaemonPreconditionError.CompilerJarsMissing(kotlincLibDir))
    }

    val projectHash = projectHashOf(absProjectPath)
    return Ok(
        DaemonSetup(
            javaBin = javaBin,
            daemonJarPath = daemonJar,
            compilerJars = compilerJars,
            socketPath = paths.daemonSocketPath(projectHash),
            logPath = paths.daemonLogPath(projectHash),
        ),
    )
}

/**
 * One-line stderr warning for a precondition failure. Kept next to
 * the error enum so every new variant is forced to supply wording at
 * the same time. The install hint in [DaemonPreconditionError.BootstrapJdkMissing]
 * intentionally names the exact command the user needs — ADR 0017
 * records this as the planned UX for activating the daemon.
 */
internal fun formatDaemonPreconditionWarning(err: DaemonPreconditionError): String = when (err) {
    DaemonPreconditionError.BootstrapJdkMissing ->
        "warning: bootstrap JDK not installed — run 'kolt install jdk $BOOTSTRAP_JDK_VERSION' to enable the warm compiler daemon"
    DaemonPreconditionError.DaemonJarMissing ->
        "warning: kolt-compiler-daemon jar not found — falling back to subprocess compile"
    is DaemonPreconditionError.CompilerJarsMissing ->
        "warning: no compiler jars found in ${err.kotlincLibDir} — falling back to subprocess compile"
}
