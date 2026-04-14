package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.infra.eprintln
import kolt.infra.fileExists
import kolt.tool.ToolchainError
import kolt.tool.installJdkToolchain

// Version of the JDK kolt uses for running the compiler daemon.
//
// Pinned deliberately: the daemon is a kolt internal, independent of
// whatever JDK the user has chosen for their project (kolt.toml
// [build] jdk). A project that pins JDK 11 must still be able to run
// the daemon, so the daemon has its own toolchain slot. Bumping this
// value is a kolt release decision — see ADR 0017 for the trade-offs
// around pinning, reproducibility, and upgrade cadence.
//
// The string is an Adoptium `latest/<feature>` key. Moving to a fully
// qualified version (`21.0.5+11`) requires hitting a different
// endpoint on the Adoptium API, which is follow-up work tracked in
// ADR 0017.
const val BOOTSTRAP_JDK_VERSION: String = "21"

// Why bootstrap JDK provisioning failed for this build. The install
// dir is carried so the warning can name the exact path kolt tried to
// populate; [cause] is the underlying toolchain failure (download,
// checksum, extract, …) already wrapped into a single user-facing
// message by `ToolchainManager`. A single flat variant is enough —
// nothing downstream classifies these; the daemon precondition path
// just surfaces them in a one-line fallback warning. ADR 0016 §5
// guarantees this is never load-bearing for correctness.
internal data class BootstrapJdkError(
    val jdkInstallDir: String,
    val cause: ToolchainError,
)

/**
 * Read-only lookup for the bootstrap JDK's `java` binary. Returns
 * `null` if the JDK is not already installed under
 * `~/.kolt/toolchains/jdk/<version>/bin/java`.
 *
 * Kept alongside [ensureBootstrapJavaBin] for tests and diagnostic
 * callers that want to probe state without triggering a download.
 * The daemon bring-up path in production goes through
 * [ensureBootstrapJavaBin] (see ADR 0017 §Decision, revised once
 * `installJdkToolchain` became `Result`-returning) so a missing
 * bootstrap JDK is auto-provisioned on first use and the daemon
 * activates immediately instead of requiring a separate install step.
 */
internal fun resolveBootstrapJavaBin(paths: KoltPaths): String? {
    val javaBin = paths.javaBin(BOOTSTRAP_JDK_VERSION)
    return if (fileExists(javaBin)) javaBin else null
}

/**
 * Resolves the bootstrap JDK's `java` binary, downloading and
 * installing the pinned version under `~/.kolt/toolchains/jdk/` on
 * first use. Returns `Ok(javaBin)` if the JDK is already installed
 * or becomes installed as a side effect, or
 * `Err(BootstrapJdkError)` if the download / checksum / extract
 * pipeline fails.
 *
 * On failure, the caller (daemon precondition resolver) must treat
 * this as a fallback signal — **never** as a build failure — because
 * the daemon is not load-bearing for correctness (ADR 0016 §5).
 * Specifically, an offline first run surfaces here as an `Err` and
 * the build silently drops back to the subprocess compile path; the
 * next online run will install the JDK and the daemon takes over.
 *
 * Progress output (`downloading jdk 21…` etc.) is routed through
 * `eprintln` rather than `println` because the auto-install fires
 * implicitly inside `doBuild` — it would otherwise interleave with
 * build stdout the user cares about. Explicit `kolt toolchain install`
 * still goes to stdout via the default `::println` progress sink on
 * [installJdkToolchain].
 *
 * Seams:
 * - [resolve] maps to [resolveBootstrapJavaBin] in production so a
 *   test can skip the download path by returning a ready-made bin.
 * - [install] maps to [installJdkToolchain] so a test can inject a
 *   fake install pipeline (success, hard failure, partial install).
 */
internal fun ensureBootstrapJavaBin(
    paths: KoltPaths,
    resolve: (KoltPaths) -> String? = ::resolveBootstrapJavaBin,
    install: (String, KoltPaths) -> Result<Unit, ToolchainError> =
        { v, p -> installJdkToolchain(v, p, progressSink = ::eprintln) },
): Result<String, BootstrapJdkError> {
    resolve(paths)?.let { return Ok(it) }

    val installDir = paths.jdkPath(BOOTSTRAP_JDK_VERSION)
    install(BOOTSTRAP_JDK_VERSION, paths).getOrElse { err ->
        return Err(BootstrapJdkError(installDir, err))
    }

    // Post-install re-check: running `resolve` again here
    // guarantees the returned path observes the same "bin/java
    // exists" invariant as the fast path above. Any mismatch means
    // the [install] lambda lied about success — either a
    // ToolchainManager bug, or a test-injected seam returning
    // `Ok(Unit)` without populating the filesystem. Either way we
    // surface it as an `Err` so the daemon path degrades
    // gracefully instead of handing back a path the JVM launcher
    // cannot exec.
    val javaBin = resolve(paths)
        ?: return Err(
            BootstrapJdkError(
                installDir,
                ToolchainError("java binary not found at ${paths.javaBin(BOOTSTRAP_JDK_VERSION)} after installation"),
            ),
        )
    return Ok(javaBin)
}
