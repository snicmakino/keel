package keel

import com.github.michaelbull.result.Result

sealed class ResolveError {
    data class InvalidDependency(val input: String) : ResolveError()
    data class Sha256Mismatch(
        val groupArtifact: String,
        val expected: String,
        val actual: String
    ) : ResolveError()
    data class DownloadFailed(val groupArtifact: String, val error: DownloadError) : ResolveError()
    data class HashComputeFailed(val groupArtifact: String, val error: Sha256Error) : ResolveError()
    data class DirectoryCreateFailed(val path: String) : ResolveError()
}

data class ResolvedDep(
    val groupArtifact: String,
    val version: String,
    val sha256: String,
    val cachePath: String,
    val transitive: Boolean = false
)

data class ResolveResult(
    val deps: List<ResolvedDep>,
    val lockChanged: Boolean
)

/**
 * Abstraction of side effects required for dependency resolution.
 * Inject a fake implementation in tests for isolation.
 */
interface ResolverDeps {
    fun fileExists(path: String): Boolean
    fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed>
    fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError>
    fun computeSha256(filePath: String): Result<String, Sha256Error>
    fun readFileContent(path: String): Result<String, OpenFailed>
}

/**
 * Resolves dependencies transitively. Downloads JARs and POMs, parses POM
 * files to discover transitive deps, and applies version conflict resolution.
 * Delegates to [resolveTransitive].
 */
fun resolve(
    config: KeelConfig,
    existingLock: Lockfile?,
    cacheBase: String,
    deps: ResolverDeps
): Result<ResolveResult, ResolveError> = resolveTransitive(config, existingLock, cacheBase, deps)

fun buildLockfileFromResolved(config: KeelConfig, deps: List<ResolvedDep>): Lockfile {
    return Lockfile(
        version = 2,
        kotlin = config.kotlin,
        jvmTarget = config.jvmTarget,
        dependencies = deps.associate {
            it.groupArtifact to LockEntry(it.version, it.sha256, it.transitive)
        }
    )
}
