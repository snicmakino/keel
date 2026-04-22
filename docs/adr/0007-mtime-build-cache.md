---
status: accepted
date: 2026-04-09
---

# ADR 0007: Skip unchanged builds by comparing mtimes in a JSON state file

## Summary

- `build/.kolt-state.json` records the mtimes of every build input after each successful build; the next build reads and compares them before invoking kotlinc. (§1)
- Source and resource directories are collapsed to `max(mtime)` across all files rather than a per-file map; deleted files are caught by `classesDirMtime`. (§2)
- `parseBuildState` swallows parse errors and returns `null`, treating a corrupt or missing state file as "no cache" rather than a hard failure. (§3)
- `classpath` is stored as a string in the state so a classpath change without a lockfile mtime change still invalidates the cache. (§4)
- Schema changes are backwards-compatible via nullable-with-default fields; a breaking change is handled by deleting `build/`. (§5)

## Context and Problem Statement

Before the build cache, `kolt build` always re-invoked kotlinc, even when nothing had changed. kotlinc carries roughly one second of fixed overhead per invocation on both JVM and Native targets, and this dominated the edit-compile-test loop.

The inputs to a kolt build are: source files under the configured source directory, resource files, `kolt.toml`, `kolt.lock`, and the compiled output directory. If none has changed since the last successful build, the output is still valid.

Three options exist for change detection: content hashing (accurate but reads every source file on every build), mtime comparison (cheap, rarely wrong in a single-user dev loop), and a full task graph with file watchers (Gradle/Bazel scale, out of scope). False negatives from mtime are rare and recoverable (`kolt clean && kolt build` always works). Reading and hashing every source file is a performance regression paid on every build in exchange for accuracy almost nobody will need.

## Decision Drivers

- No-op build wall time must be near-instant (dominated by process startup, not compilation).
- `kolt clean` must clear the cache automatically with no second directory to manage.
- A corrupt or partially written state file must degrade to a rebuild, not a hard failure.
- Debugging a spurious rebuild must be possible by reading the state file directly.

## Decision Outcome

Chosen option: **mtime-based JSON state file at `build/.kolt-state.json`**, because it eliminates the kotlinc overhead on no-op builds with minimal implementation complexity.

### §1 State file and comparison

`BuildState` in `build/BuildCache.kt`:

```kotlin
data class BuildState(
    val configMtime: Long,
    val sourcesNewestMtime: Long,
    val classesDirMtime: Long?,
    val lockfileMtime: Long?,
    val classpath: String? = null,
    val resourcesNewestMtime: Long? = null
)
```

`isBuildUpToDate(current, cached)` compares every field for equality. Any difference, or a missing cached state, causes a full rebuild. The state is written once after a successful build. `build/.kolt-state.json` lives inside `build/`, so `kolt clean` removes it automatically.

### §2 Directory mtime collapse

`sourcesNewestMtime` and `resourcesNewestMtime` are `max(mtime)` across all files in the directory. If any file is modified, the max increases. Deleted files are caught by `classesDirMtime` — the compiled output does not update for deletions, so its mtime diverges from the expected post-rebuild value. `kolt.lock` may not exist yet for a project with no dependencies; `resources/` is optional; the classes dir does not exist on the first build — hence the nullable fields.

### §3 Corrupt-state handling

`parseBuildState` swallows any parse error and returns `null`. A botched write (crash mid-serialise, partial disk flush) degrades cleanly to a rebuild rather than an error.

### §4 Classpath string

`classpath` stores the full serialised classpath string. A manual edit of a dependency that changes the classpath without moving the lockfile mtime (rare) is still detected via direct string comparison. If the resolver produces a different ordering without changing content, the comparison spuriously invalidates; in practice the resolver is deterministic, so this has not occurred.

### §5 Schema evolution

Adding a field as nullable-with-default is backwards-compatible. A breaking schema change requires a cache bust: delete `build/`. No cache-key versioning is necessary for additive changes. Future content-hash verification for specific fields can be added as new nullable `BuildState` columns without touching the comparison logic.

### Consequences

**Positive**
- No-op builds cost a handful of `stat` calls and one JSON parse; process startup is now the dominant cost.
- All ordinary invalidation paths work: edit a source file, change `kolt.toml`, run `kolt install`, delete `build/`.
- The state file is human-readable — compare `build/.kolt-state.json` against `stat` on real files to debug a spurious rebuild.

**Negative**
- Filesystems with 1-second mtime resolution (older ext4, FAT32, WSL2 9p) can miss a modification made within the same second as the last build. CI systems regenerating files programmatically can hit this; workaround is `kolt clean`.
- A file whose mtime is set backwards (`touch -d`) will not trigger a rebuild.
- A cached JAR manually edited without changing its path or the lockfile mtime is not detected. SHA-verified cache entries make this unreachable in normal use.
- `max(mtime)` cannot identify which source file changed — sufficient for "rebuild or not", but insufficient for future per-file incremental compilation inside kotlinc.

### Confirmation

`BuildCache.kt` is the single implementation. Tests in `BuildCacheTest.kt` cover the mtime comparison logic and the corrupt-state fallback. The state file location (`build/.kolt-state.json`) is verified to be under `build/` so that `kolt clean` removes it.

## Alternatives considered

1. **SHA-256 of every source file.** Rejected. Reading and hashing every source file on every build reintroduces the overhead we are trying to eliminate.
2. **Gradle-style task graph with up-to-date checking.** Rejected as far out of scope. Rebuilding that machinery defeats the purpose of kolt.
3. **`mtime + size` combined key.** Considered. `size` changes for same-second edits, reducing false negatives slightly. Cost is marginal complexity; revisit if mtime-only produces bug reports.
4. **kotlinc `-Xbuild-file` incremental cache.** Rejected. kotlinc's incremental support is version-specific and oriented at Gradle's execution model; using it from a CLI would require replicating a large slice of Gradle's inputs-tracking surface.

## Related

- `src/nativeMain/kotlin/kolt/build/BuildCache.kt` — `BuildState`, `isBuildUpToDate`, `serializeBuildState`, `parseBuildState`
- Commit `4d94ce0` — initial mtime-based build cache
- ADR 0003 — same serialisation policy (JSON for internal machine-readable files)
