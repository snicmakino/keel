package keel

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse

private data class QueueEntry(
    val groupArtifact: String,
    val version: String
)

/**
 * Resolves dependencies transitively via BFS.
 * 1. Seed queue with direct deps
 * 2. For each dep: download JAR + POM if not cached, parse POM
 * 3. Extract transitive deps (filter scope/optional)
 * 4. Version conflicts: direct deps always win, otherwise highest version wins
 * 5. Cycle detection via visited set
 * 6. Parent POM chain for dependencyManagement version lookup
 */
fun resolveTransitive(
    config: KeelConfig,
    existingLock: Lockfile?,
    cacheBase: String,
    deps: ResolverDeps
): Result<ResolveResult, ResolveError> {
    var lockChanged = false

    // Track resolved versions: groupArtifact -> (version, isDirect)
    val resolvedVersions = mutableMapOf<String, Pair<String, Boolean>>()
    val queue = ArrayDeque<QueueEntry>()
    val visited = mutableSetOf<String>() // "group:artifact:version"

    // Seed with direct deps
    for ((groupArtifact, version) in config.dependencies) {
        resolvedVersions[groupArtifact] = Pair(version, true)
        queue.addLast(QueueEntry(groupArtifact, version))
    }

    // BFS
    while (queue.isNotEmpty()) {
        val entry = queue.removeFirst()
        val visitKey = "${entry.groupArtifact}:${entry.version}"
        if (visitKey in visited) continue
        visited.add(visitKey)

        val coord = parseCoordinate(entry.groupArtifact, entry.version).getOrElse {
            return Err(ResolveError.InvalidDependency(entry.groupArtifact))
        }

        // Download POM if not cached
        val pomCachePath = "$cacheBase/${buildPomCachePath(coord)}"
        if (!deps.fileExists(pomCachePath)) {
            val parentDir = pomCachePath.substringBeforeLast('/')
            deps.ensureDirectoryRecursive(parentDir).getOrElse {
                return Err(ResolveError.DirectoryCreateFailed(parentDir))
            }
            val pomUrl = buildPomDownloadUrl(coord)
            deps.downloadFile(pomUrl, pomCachePath).getOrElse { error ->
                return Err(ResolveError.DownloadFailed(entry.groupArtifact, error))
            }
        }

        // Parse POM
        val pomContent = deps.readFileContent(pomCachePath).getOrElse {
            // If POM can't be read, skip transitive deps for this artifact
            continue
        }
        val pomInfo = parsePom(pomContent).getOrElse {
            // If POM can't be parsed, skip transitive deps
            continue
        }

        // Resolve parent POM chain for dependencyManagement
        val depMgmt = collectDependencyManagement(pomInfo, cacheBase, deps)

        // Process transitive dependencies
        for (pomDep in pomInfo.dependencies) {
            // Filter scope
            val scope = pomDep.scope ?: "compile"
            if (scope != "compile" && scope != "runtime") continue

            // Filter optional
            if (pomDep.optional) continue

            val depGroupArtifact = "${pomDep.groupId}:${pomDep.artifactId}"

            // Resolve version from dependencyManagement if not specified
            val depVersion = pomDep.version
                ?: depMgmt[depGroupArtifact]
                ?: continue // Skip if version can't be determined

            val existing = resolvedVersions[depGroupArtifact]
            if (existing != null) {
                val (existingVersion, isDirect) = existing
                if (isDirect) {
                    // Direct deps always win
                    continue
                }
                // Transitive: highest version wins
                if (compareVersions(depVersion, existingVersion) <= 0) {
                    continue
                }
            }

            resolvedVersions[depGroupArtifact] = Pair(depVersion, false)
            queue.addLast(QueueEntry(depGroupArtifact, depVersion))
        }
    }

    // Now download JARs and compute hashes for all resolved deps
    val resolvedDeps = mutableListOf<ResolvedDep>()
    for ((groupArtifact, versionAndDirect) in resolvedVersions) {
        val (version, isDirect) = versionAndDirect
        val coord = parseCoordinate(groupArtifact, version).getOrElse {
            return Err(ResolveError.InvalidDependency(groupArtifact))
        }

        val relativePath = buildCachePath(coord)
        val fullCachePath = "$cacheBase/$relativePath"
        val lockEntry = existingLock?.dependencies?.get(groupArtifact)

        // Download JAR if not cached
        if (!deps.fileExists(fullCachePath)) {
            val parentDir = fullCachePath.substringBeforeLast('/')
            deps.ensureDirectoryRecursive(parentDir).getOrElse {
                return Err(ResolveError.DirectoryCreateFailed(parentDir))
            }
            val url = buildDownloadUrl(coord)
            deps.downloadFile(url, fullCachePath).getOrElse { error ->
                return Err(ResolveError.DownloadFailed(groupArtifact, error))
            }
            lockChanged = true
        }

        // Compute SHA256
        val hash = deps.computeSha256(fullCachePath).getOrElse { error ->
            return Err(ResolveError.HashComputeFailed(groupArtifact, error))
        }

        // Verify against lockfile
        if (lockEntry != null) {
            if (lockEntry.version != version) {
                lockChanged = true
            } else if (lockEntry.sha256 != hash) {
                return Err(ResolveError.Sha256Mismatch(groupArtifact, lockEntry.sha256, hash))
            }
        } else {
            lockChanged = true
        }

        resolvedDeps.add(ResolvedDep(groupArtifact, version, hash, fullCachePath, transitive = !isDirect))
    }

    if (existingLock != null) {
        if (existingLock.kotlin != config.kotlin || existingLock.jvmTarget != config.jvmTarget) {
            lockChanged = true
        }
        for (key in existingLock.dependencies.keys) {
            if (key !in resolvedVersions) {
                lockChanged = true
            }
        }
    }

    return Ok(ResolveResult(resolvedDeps, lockChanged))
}

/**
 * Collects dependencyManagement entries from the POM and its parent chain.
 * Returns a map of groupArtifact -> version.
 */
private fun collectDependencyManagement(
    pomInfo: PomInfo,
    cacheBase: String,
    deps: ResolverDeps,
    depth: Int = 0
): Map<String, String> {
    if (depth > 10) return emptyMap()

    val result = mutableMapOf<String, String>()

    // Add current POM's dependencyManagement
    for (managed in pomInfo.dependencyManagement) {
        val key = "${managed.groupId}:${managed.artifactId}"
        if (managed.version != null && key !in result) {
            result[key] = managed.version
        }
    }

    // Follow parent POM chain
    val parent = pomInfo.parent ?: return result
    val parentCoord = Coordinate(parent.groupId, parent.artifactId, parent.version)
    val parentPomPath = "$cacheBase/${buildPomCachePath(parentCoord)}"

    // Download parent POM if not cached
    if (!deps.fileExists(parentPomPath)) {
        val parentDir = parentPomPath.substringBeforeLast('/')
        deps.ensureDirectoryRecursive(parentDir).getOrElse { return result }
        val parentUrl = buildPomDownloadUrl(parentCoord)
        deps.downloadFile(parentUrl, parentPomPath).getOrElse { return result }
    }

    val parentContent = deps.readFileContent(parentPomPath).getOrElse { return result }
    val parentPomInfo = parsePom(parentContent).getOrElse { return result }

    // Parent entries have lower priority (don't override child's)
    val parentMgmt = collectDependencyManagement(parentPomInfo, cacheBase, deps, depth + 1)
    for ((key, version) in parentMgmt) {
        if (key !in result) {
            result[key] = version
        }
    }

    return result
}
