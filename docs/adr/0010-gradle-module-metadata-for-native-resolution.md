---
status: accepted
date: 2026-04-13
---

# ADR 0010: Use Gradle Module Metadata for Kotlin/Native dependency resolution

## Summary

- Native artifacts (`.klib`) and their transitive dependencies are not described in POMs — the only authoritative source is Gradle Module Metadata (`.module` JSON). (§1)
- `NativeResolver` walks `.module` files exclusively; `TransitiveResolver` (JVM) walks POMs, calling `parseJvmRedirect` for KMP redirects only. (§2)
- The dispatch is a single `if (config.target == "native")` in `Resolver.resolve()`; the two pipelines share `ResolverDeps`, `ResolveError`, `ResolveResult`, and download helpers. (§3)
- For each dependency, `NativeResolver` follows the `available-at` redirect to the target-specific module, extracts the `.klib` url + sha256 from `files[]`, and recurses on `dependencies[]`. (§4)
- sha256 values are read from `files[].sha256` in the metadata — not fetched from `.sha256` sidecars — eliminating one round-trip per artifact. (§5)

## Context and Problem Statement

kolt supports `target = "jvm"` and `target = "native"` (see #16). JVM builds consume `.jar` files whose dependency information lives in Maven POMs. Native builds consume `.klib` files; modern Kotlin Multiplatform libraries (kotlinx-coroutines, kotlinx-serialization, ktor) publish per-target `.klib` artifacts via Gradle Module Metadata, not via POMs.

A spike against `kotlinx-coroutines-core:1.9.0` confirmed that POMs cannot carry the required information: the POM describes only the JVM jar; the native variant's `available-at` redirect (e.g. `kotlinx-coroutines-core` → `kotlinx-coroutines-core-linuxx64`) is encoded as a metadata variant, not a Maven dependency; transitive dependencies for the native variant live in `variants[].dependencies[]`; and `.klib` file hashes live in `variants[].files[]`. `.module` files are not optional decoration — they are the only place native resolution information lives.

## Decision Drivers

- Must resolve the correct `.klib` for `linux_x64` without guessing artifact name conventions.
- sha256 verification must not require a separate sidecar fetch per artifact.
- JVM and native resolvers must be independently evolvable without cross-contamination.
- `available-at` coordinates must be followed verbatim; no name inference.

## Decision Outcome

Chosen option: **two parallel pipelines with metadata-only native resolution**, because POMs and Gradle metadata have fundamentally different shapes; a unified BFS that branches on target type would push conditional logic into every helper.

### §1 POMs cannot describe native artifacts

The root POM of a KMP library has a `<dependencies>` section, but it describes JVM artifacts only. There is no Maven mechanism to encode a per-target `.klib` coordinate, file hash, or native-variant transitive graph. Any approach starting from POMs for native would require fabricating information the POM does not contain.

### §2 Two parallel resolvers

`TransitiveResolver` + `PomParser` handle JVM; `NativeResolver` handles native. They are not unified. The JVM path calls `parseJvmRedirect` from `GradleMetadata.kt` to follow KMP redirects, then switches back to POM-based walking (see ADR 0005). The native path uses Gradle metadata for everything.

### §3 Dispatch

```kotlin
fun resolve(...): Result<ResolveResult, ResolveError> =
    if (config.target == "native") resolveNative(config, cacheBase, deps)
    else resolveTransitive(config, existingLock, cacheBase, deps)
```

Shared: `ResolverDeps`, `ResolveError`, `ResolveResult`, `downloadFromRepositories`, `fetchAndRead`.

### §4 Native resolution walk

For each dependency, `NativeResolver`:
1. Fetches the root `.module` and finds the `available-at` redirect for the current native target via `parseNativeRedirect`.
2. Fetches the redirect-target `.module` and extracts the `.klib` file reference (url + sha256) and `dependencies[]` via `parseNativeArtifact`.
3. Verifies the `.klib` sha256 against the value declared in `files[]`.
4. Recurses on the variant's `dependencies[]`.

`GradleMetadata.kt` reads four attributes (`platform.type`, `native.target`, `usage`, `category`), the `available-at` block, and `files[]` / `dependencies[]`. The full Gradle metadata 1.1 spec is not implemented. The decoder is configured `ignoreUnknownKeys = true` so schema evolution does not break kolt.

### §5 sha256 from metadata

Hashes are read from `files[].sha256` in the metadata rather than fetched from a separate `.sha256` sidecar, removing one network round-trip and one failure mode per artifact.

### Consequences

**Positive**
- kolt picks up exactly the `.klib` published for `linux_x64`, the version the publisher pinned, and the transitive native dependencies needed at link time.
- No POM-fitting hacks; no invented "native classifier" coordinates; `available-at` is followed verbatim.
- sha256 sourced from metadata eliminates a sidecar round-trip per artifact.
- Native and JVM resolvers evolve independently; bugs in one cannot break the other.

**Negative**
- `TransitiveResolver` and `NativeResolver` duplicate the BFS skeleton, highest-version-wins rule, and direct-deps-win precedence — bug fixes may need porting to both.
- `PomParser.kt` (XML) and `GradleMetadata.kt` (JSON) are separate code paths to maintain.
- Libraries published with a POM only and no `.module` cause `parseNativeRedirect` to return `null`, surfacing `ResolveError.NoNativeVariant` — correct behaviour, but may surprise users porting JVM projects.

### Confirmation

Verified by `NativeResolverTest.kt` integration tests that download real KMP artifacts and confirm the resolved `.klib` list matches the expected target-specific variants.

## Alternatives considered

1. **Unify under a single `Resolver` that branches inside the BFS.** Rejected — POMs and Gradle metadata differ in structure (XML element tree vs JSON variant array, parent POM chain vs `available-at` redirect, `<dependencyManagement>` vs metadata version constraints); branching would extend into every helper, producing more conditional code than two pipelines.
2. **Treat `.module` files as supplemental metadata on top of POMs.** Rejected — the POM does not describe native artifacts at all; there is nothing in the POM to augment.
3. **Walk only the root `.module` and skip `available-at`, assuming `<module>-<targetname>` by convention.** Rejected — Android Native targets, special-case names, and future targets break the convention; `available-at.module` must be used verbatim.
4. **Use Coursier as an external dependency.** Out of scope — kolt is a single self-contained Kotlin/Native binary and does not shell out to JVM tools for core operations.

## Related

- #16 — Kotlin/Native target support (parent issue)
- PR #53 — Phase B-1, parser-only implementation
- PR #55 — Phase B-2, `NativeResolver` wired into `Resolver.resolve`
- ADR 0005 — JVM redirect handling; shares `GradleMetadata.kt` with this resolver
- ADR 0011 — kotlin-stdlib skip; load-bearing assumption that depends on this resolver design
