---
status: accepted
date: 2026-04-13
---

# ADR 0011: Skip kotlin-stdlib in Kotlin/Native dependency resolution

## Summary

- Every native KMP variant declares `kotlin-stdlib` as a transitive dependency, but resolving it fails or produces a double-link because konanc bundles its own stdlib and auto-links it. (§1)
- `NativeResolver` silently skips `org.jetbrains.kotlin:kotlin-stdlib` and `org.jetbrains.kotlin:kotlin-stdlib-common` at all three entry points: direct-dep validation, direct-dep seeding, and transitive BFS enqueueing. (§2)
- `-nostdlib` is not passed to konanc; bundled auto-linking is the correct mechanism. (§3)
- `kotlin-stdlib-common` was added to the skip list after observing it in `org.kotlincrypto.hash:sha2-256:0.2.7`'s transitive closure — it is a pre-Gradle-metadata artifact (POM only), causing the native `.module` fetch to 404. (§4)

## Context and Problem Statement

When `NativeResolver` walks the Gradle Module Metadata of a Kotlin Multiplatform library, every native variant declares `org.jetbrains.kotlin:kotlin-stdlib` as a transitive dependency. For example, the linuxX64 variant of `kotlinx-coroutines-core:1.9.0` lists:

```json
{
  "dependencies": [
    { "group": "org.jetbrains.kotlinx", "module": "atomicfu",
      "version": { "requires": "0.25.0" } },
    { "group": "org.jetbrains.kotlin", "module": "kotlin-stdlib",
      "version": { "requires": "2.0.0" } }
  ]
}
```

Resolving `kotlin-stdlib` like any other dependency fails for two independent reasons:

1. **konanc bundles its own stdlib.** The Kotlin/Native compiler distribution ships a `klib/common/stdlib` klib in `$installation/klib/`, and konanc auto-links it on every compilation. The existence of a `-nostdlib` flag ("Don't link with stdlib") confirms that linking happens by default — there would be nothing to opt out of otherwise.
2. **Maven Central does not publish a usable native stdlib variant.** The root `kotlin-stdlib` module exposes a JVM jar; the native variants live elsewhere. The resolver would either fail to find a `linux_x64` variant (`ResolveError.NoNativeVariant`) or, if it found one, double-link it against konanc's bundled copy and produce a link error.

A spike during Phase B research (PR #53) confirmed the bundling behaviour: a Hello World native binary calling `println` from `kotlin.io` builds and runs without any `-library` argument and without kolt resolving any dependency.

## Decision Drivers

- Every native build that uses a KMP library must not fail at resolution or link.
- kolt must not become responsible for versioning stdlib against the bundled konanc release.
- `~/.kolt/cache/` must not accumulate stdlib klibs that are never used.

## Decision Outcome

Chosen option: **silent skip at all three NativeResolver entry points**, because skipping at enqueue/seed is the only place that prevents both the resolution failure and the double-link failure mode entirely.

### §1 Why resolving stdlib fails or double-links

The root cause is that konanc already owns stdlib. Any attempt to resolve it from Maven Central either surfaces `ResolveError.NoNativeVariant` (no usable `linux_x64` variant found) or produces a link error from double-linking the downloaded klib against konanc's bundled copy.

### §2 Skip predicate and application points

```kotlin
private fun isKotlinStdlib(groupArtifact: String): Boolean =
    groupArtifact == "org.jetbrains.kotlin:kotlin-stdlib" ||
        groupArtifact == "org.jetbrains.kotlin:kotlin-stdlib-common"
```

Applied in three places:
1. **Direct-dependency validation loop** — skipped before coordinate validation, even if the user writes `kotlin-stdlib` in `kolt.toml`.
2. **Direct-dependency seeding loop** — not added to the BFS queue.
3. **Transitive BFS enqueueing** — dropped before the highest-version-wins comparison when walking `variants[].dependencies[]`.

The skip is silent. No warning is emitted — this is a property of how Kotlin/Native is distributed, not a project-level mistake.

### §3 No `-nostdlib`

`-nostdlib` is not passed to konanc. kolt relies on default auto-linking. The user's `kotlin = "<version>"` in `kolt.toml` already pins the konanc release, and the bundled stdlib is the correct one for that release by construction. Opting out and re-supplying stdlib would tie kolt's release cadence to publishing a matching stdlib per supported Kotlin version.

### §4 `kotlin-stdlib-common` extension

`kotlin-stdlib-common` was added alongside `kotlin-stdlib` after observing it in the transitive closure of `org.kotlincrypto.hash:sha2-256:0.2.7`, which pins `kotlin-stdlib-common:1.8.21`. That artifact is pre-Gradle-metadata (POM only), causing the native `.module` fetch to 404 in the resolver. Future related modules (e.g. `kotlin-stdlib-jdk8`) would need explicit additions if they appeared in a native variant's dependencies; blanket-skipping the full `org.jetbrains.kotlin:*` group was rejected as overreach (see Alternatives).

### Consequences

**Positive**
- Every native build using a KMP library works without a resolution or link-stage failure.
- kolt never has to decide which stdlib version to fetch; the bundled one is always correct.
- `~/.kolt/cache/` does not accumulate stdlib klibs that would never be used.

**Negative**
- If a future Kotlin/Native release stops bundling stdlib (very unlikely), kolt will silently break — the skip would still drop stdlib from resolution, but konanc would no longer auto-link it. The failure would surface as a link-time "unresolved reference" from konanc, not a clean kolt error.
- A user who explicitly writes `kotlin-stdlib` in `[dependencies]` will see it disappear from the resolved set with no message — intentional but potentially confusing when porting a JVM project.
- The skip list (`kotlin-stdlib`, `kotlin-stdlib-common`) is hard-coded in `NativeResolver.kt`; new stdlib-family modules in native variant dependencies require explicit additions.
- JVM path is unaffected: `TransitiveResolver` resolves `kotlin-stdlib` from POMs as usual. The skip applies only to the native pipeline.

### Confirmation

Confirmed by the Phase B spike (PR #53): Hello World native binary linking without any `-library` argument. Covered by integration tests in `NativeResolverTest.kt` that verify resolved klib lists do not include stdlib entries.

## Alternatives considered

1. **Pass `-nostdlib` and resolve `kotlin-stdlib` from Maven Central.** Rejected — no native `linux_x64` variant of `kotlin-stdlib` is published in the way the resolver expects; even if there were, it would tie kolt's release cadence to publishing a matching stdlib per supported `kotlin = "<version>"`.
2. **Filter stdlib only at the materialise step (after BFS).** Rejected — BFS would still walk `kotlin-stdlib`'s metadata, fail to find a native variant, and surface `ResolveError.NoNativeVariant` to the user. Skipping at the enqueue/seed step is the only place that prevents the failure mode entirely.
3. **Emit a warning when the skip fires.** Rejected — the transitive case fires on every native build, producing noise that does not lead to any user action.
4. **Blanket-skip the `org.jetbrains.kotlin:*` group.** Rejected as overreach — only `kotlin-stdlib` and `kotlin-stdlib-common` are known to need skipping; other modules in that group should be evaluated case by case.

## Related

- #16 — Kotlin/Native target support (parent issue)
- PR #53 — Phase B-1, spike confirming konanc stdlib bundling
- PR #55 — Phase B-2, where the skip was introduced
- ADR 0010 — Gradle Module Metadata for native resolution; provides the resolver context this skip lives inside
