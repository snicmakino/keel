---
status: accepted
date: 2026-04-09
---

# ADR 0004: Separate pure algorithms from I/O orchestration

## Summary

- Every non-trivial subsystem splits into a pure module (value-in / value-out, no side effects) and an I/O module (filesystem, network, process). (§1)
- Pure and I/O halves communicate through function parameters only — no shared mutable state visible from the pure side. (§2)
- The resolver's pure core (`Resolution.kt`) takes a `pomLookup: (groupArtifact, version) -> PomInfo?` parameter and never touches the network or filesystem itself. (§3)
- Purity is enforced by convention and package layout, not by the type system; kolt does not use an effect system. (§4)

## Context and Problem Statement

Phase 3 left the transitive resolver as a single file (`TransitiveResolver.kt`) interleaving BFS logic, HTTP downloads, JAR writes, sha256 checks, and a memoisation map. Testing one corner case — a diamond dependency where two paths disagree on version — required a fake HTTP server or temp-directory setup. A single bug in version comparison needed an integration test to reproduce.

`Builder.kt` and `Runner.kt` had been pure argv builders from day one and were trivial to test. The resolver needed the same treatment after the fact. Coursier (the JVM dependency resolver) had solved this with a pattern worth copying: the algorithm is a pure state machine taking a "fetch function" as a parameter and never touching the network itself.

## Decision Drivers

- Diamond dependencies, parent POM chains, version intervals, exclusion propagation, and cycle detection must be exercisable with no filesystem or network access.
- Adding a new download failure mode must not require changes to the resolution algorithm.
- The pure core must be readable as a specification of the rules without I/O noise.

## Decision Outcome

Chosen option: **pure-core / I/O-shell split via function parameters**, applied to every subsystem with enough logic to warrant its own tests.

### §1 The split applies across subsystems

| Pure module | I/O module | Role |
|---|---|---|
| `Resolution.kt` | `TransitiveResolver.kt` | Dependency graph BFS |
| `PomParser.kt` | `TransitiveResolver.kt` | POM XML parsing |
| `GradleMetadata.kt` | `NativeResolver.kt` | `.module` parsing, redirect |
| `VersionCompare.kt` | (used by `Resolution.kt`) | Maven version comparison |
| `Builder.kt` | `BuildCommands.kt` | kotlinc argv construction |
| `Runner.kt` | `BuildCommands.kt` | `java -jar` argv construction |
| `TestBuilder.kt` | `BuildCommands.kt` | Test compile argv |
| `TestRunner.kt` | `BuildCommands.kt` | JUnit Platform launcher argv |
| `TestDeps.kt` | `BuildCommands.kt` | Auto-injected test dependencies |
| `Formatter.kt` | `FormatCommands.kt` | ktfmt argv construction |
| `AddDependency.kt` | `DependencyCommands.kt` | TOML string manipulation |
| `DepsTree.kt` | `DependencyCommands.kt` | Dependency tree ASCII rendering |

### §2 Communication via function parameters only

Pure modules receive their I/O-backed inputs as function parameters. No I/O reference is stored as a field on the pure side. The `pomLookup` closure that `Resolution.kt` receives captures a mutable cache (`pomCache`) inside `TransitiveResolver.kt`; readers must understand the closure is stateful to reason about repeated lookups.

### §3 Resolver split — concrete shape

`Resolution.kt` exposes `resolveGraph(deps, pomLookup)`. It walks the dependency graph via BFS, applies highest-version-wins, propagates exclusions, and detects cycles. It never reads files, makes HTTP calls, or computes hashes.

`TransitiveResolver.kt` provides `createPomLookup(cacheBase, downloader, ...)` — a closure encapsulating POM downloading, disk caching, and parsing — and `materialize(nodes)` which downloads and verifies JARs for the resolved graph. `TransitiveResolver.resolve()` is the thin orchestrator: build the `pomLookup`, call `resolveGraph`, then `materialize`.

`DepsTree.kt` reuses the same `pomLookup` signature for the `kolt tree` command without triggering downloads — a direct payoff of the separation. kolt borrows the *separation* from Coursier, not Coursier's explicit `Done / Missing / Continue` state-machine encoding.

### §4 Purity by convention

Nothing in the type system prevents adding `downloader.download(...)` inside `Resolution.kt`. The rule is maintained by code review and package layout: pure modules do not import `infra/` or `platform.posix`. kolt does not use an effect system (Arrow, monadic IO); the convention-based split delivers most of the benefits at zero dependency cost.

### Consequences

**Positive**
- `ResolutionTest` constructs a hand-written `pomLookup` from a `Map` literal. Every BFS corner case — diamonds, parent POM chains, version intervals, exclusion propagation, cycles — is a table-driven unit test with no filesystem, no network, no temp directories.
- `Resolution.kt` reads as a specification of the rules; the reader never holds two concerns at once.
- Callers can substitute an offline lookup built from `kolt.lock` (e.g. `DepsTree.kt`) without touching resolver logic.
- The two native resolvers (`TransitiveResolver` for jvm, `NativeResolver` for native) share the same pure helpers (`VersionCompare`, `PomExclusion` matching); bug fixes port cleanly.
- I/O errors stay in the I/O wrapper as `Result<_, E>`; the pure core only sees that `pomLookup` can return `null`.

**Negative**
- The split is discipline, not enforcement — a contributor unfamiliar with the convention can break it without a compile error.
- Following a call flow means jumping between two files (`Resolution.kt` ↔ `TransitiveResolver.kt`).
- Very small pieces of logic that happen to call the filesystem once are not split; the rule applies only where the pure core has enough logic to warrant its own tests.

### Confirmation

Code review checks that pure modules do not import `infra/` or `platform.posix`. The absence of I/O in `Resolution.kt` is verified by the fact that `ResolutionTest` runs with no I/O setup.

## Alternatives considered

1. **Leave I/O interleaved; rely on integration tests.** Rejected. Integration tests over a real cache directory were slow, flaky (mtime races, concurrent temp dirs), and could not cover the dozen version-conflict rules at unit-test granularity.
2. **Mock the `Downloader` interface.** Rejected. Mocking the fetcher still leaves BFS logic interleaved with cache-layer and parser code; you cannot unit-test the BFS in isolation.
3. **Adopt an effect system (Arrow `IO`, `Resource`).** Rejected. Too heavy a dependency and too novel a pattern for a Kotlin/Native CLI. The convention-based split achieves the same separation at zero dependency cost.
4. **Port Coursier's full state-machine resolution verbatim.** Rejected. Coursier's state machine handles concurrent fetching, conflict back-propagation, and reconciliation strategies kolt does not need. The simpler BFS with highest-version-wins is sufficient for the supported scope.

## Related

- `src/nativeMain/kotlin/kolt/resolve/Resolution.kt` — pure BFS
- `src/nativeMain/kotlin/kolt/resolve/TransitiveResolver.kt` — I/O orchestrator and `pomLookup` factory
- Commit `91d77fd` — initial extraction of `resolveGraph` from `TransitiveResolver`
- Coursier — design reference for algorithm/fetch separation
