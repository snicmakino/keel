package kolt.build.daemon

import kolt.config.KoltPaths
import kolt.infra.fileExists

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

/**
 * Read-only lookup for the bootstrap JDK's `java` binary. Returns
 * `null` if the JDK is not already installed under
 * `~/.kolt/toolchains/jdk/<version>/bin/java`.
 *
 * This function intentionally does **not** trigger a download. The
 * daemon is never load-bearing for correctness (ADR 0016 §5); an
 * unavailable bootstrap JDK must degrade to the subprocess compile
 * path, never to a kolt process exit. Auto-install of the bootstrap
 * JDK is future work (tracked in ADR 0017 follow-ups) that requires
 * refactoring the existing `ensureJdkBins` path to return a
 * `Result<_, _>` instead of calling `exitProcess` on failure.
 *
 * Today the install happens via the user-invoked command
 * `kolt install jdk 21` — the daemon then activates automatically
 * on the next build. A caller that receives `null` should fall back
 * to [kolt.build.SubprocessCompilerBackend] and optionally print a
 * one-line hint pointing at `kolt install jdk $BOOTSTRAP_JDK_VERSION`.
 */
internal fun resolveBootstrapJavaBin(paths: KoltPaths): String? {
    val javaBin = paths.javaBin(BOOTSTRAP_JDK_VERSION)
    return if (fileExists(javaBin)) javaBin else null
}
