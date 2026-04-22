---
status: superseded by ADR-0014
date: 2026-04-13
---

# ADR 0013: Compile native tests in a single konanc invocation

## Summary

- `kolt test` on native compiles main sources and test sources together in one `konanc -p program -generate-test-runner` invocation (α). (§1)
- `-e` is omitted; `-generate-test-runner` synthesises the entry during lowering. (§1)
- `doNativeTest` bypasses `doNativeBuild` — production and test binaries are independent konanc calls. (§2)
- `kotlin.test` is bundled in konanc; `TestDeps.autoInjectedTestDeps` returns `emptyMap()` for native. (§3)
- **Superseded by ADR 0014**: konanc silently no-ops plugin registrars under `-p program`, so two-stage library → link is required for compiler plugin support (#62).

## Context and Problem Statement

Phase D of Kotlin/Native support (#16 / PR #58) had to decide how `kolt test` produces a runnable test binary when `target = "native"`. The JVM path reuses `build/classes` from `doBuild()` as input to a second `kotlinc` invocation, then launches JUnit Platform. konanc does not share that model: the unit of production is a `.klib` or `.kexe`, there is no ".class directory as input" equivalent, and the test runner is synthesised by a compiler lowering pass (`-generate-test-runner`) rather than wired externally.

Research during Phase D also confirmed that `kotlin.test` is bundled in the konanc distribution — no Maven resolution is required, unlike the JVM path's auto-injection of `kotlin-test-junit5`.

Two approaches were plausible:

**(α) Single-step.** Pass main sources and test sources to konanc together with `-generate-test-runner`, producing `build/<name>-test.kexe`. `internal` visibility across main and test works naturally — both sides belong to the same compilation unit.

**(β) Two-step.** Compile main sources to a `.klib`, then compile test sources in a second invocation with `-library prod.klib` and `-friend-modules prod.klib`. `-friend-modules` is required to restore `internal` visibility across compilation units.

β is the prerequisite for any `.klib` publication story and is structurally closer to how KGP handles native tests. A Phase D spike verified that α works end-to-end against konanc 2.1.0 and is simpler given kolt's single-binary-output scope.

## Decision Drivers

- Minimal diff: no new artifact, no caching changes, no new resolver mode
- `internal` visibility across main and test without `-friend-modules` plumbing
- Exit-code pass/fail uniform with JVM path
- Reversible to β later without user-visible changes

## Decision Outcome

Chosen option: **α — single-step compile**, because it solves the problem with the smallest structural change and the move to β is a refactor, not a rewrite.

### §1 Invocation shape

`nativeTestBuildCommand` produces:

```
konanc <main-sources> <test-sources> \
    -p program \
    -generate-test-runner \
    -l <klib1> -l <klib2> ... \
    -o build/<name>-test
```

`-e` is omitted: `-generate-test-runner` synthesises a `main()` that calls `testLauncherEntryPoint(args)`; passing `-e` alongside it would conflict.

### §2 `doNativeTest` bypasses `doNativeBuild`

`doTest` dispatches on target; the native branch calls `doNativeTest` directly — it does **not** invoke `doBuild()` first. The production binary (`build/<name>.kexe`) and the test binary (`build/<name>-test.kexe`) are produced by independent konanc calls; neither is an input to the other.

Pass/fail signalling uses the test binary's exit code: `-generate-test-runner` calls `exitProcess(1)` on any failed test, which maps onto the existing `ProcessError.NonZeroExit` → `EXIT_TEST_ERROR` flow.

### §3 No test dependency resolution

`TestDeps.autoInjectedTestDeps` returns `emptyMap()` for native. `kotlin.test` is bundled in konanc; `resolveNativeDependencies` from Phase B handles transitive klib resolution, and nothing extra is needed for the test framework. Users coming from Gradle may expect to declare `kotlin("test")`; kolt accepts the entry but silently no-ops it for native.

### Consequences

**Positive**
- Minimal implementation: `doNativeBuild` is untouched, no new `.klib` artifact, no `BuildState` caching changes.
- `internal` visibility works without `-friend-modules` plumbing.
- `resolveNativeDependencies` is reused as-is; no new resolver mode for test-only dependencies.
- Exit code signalling is uniform with the JVM path.

**Negative**
- No incremental test builds: every `kolt test` triggers a full `konanc` compile of main and test sources. On native this is ~20s empirically (Phase D E2E runs). Tracked as #59.
- `kolt build && kolt test` compiles main sources twice — once for the production binary, once inside the test binary.
- Structural asymmetry with the JVM `doTest`, which reuses `doBuild()` artifacts. Any future unification needs a target-aware branch regardless.
- A-test-only-edit loop cannot skip main recompilation even with an mtime cache: the cache key must include main sources (they participated in the compile), so only a β-style split can help.

### Confirmation

End-to-end: `kolt test` on a `target = "native"` project exits non-zero on a failing `@Test` and zero on all passing. `kotlin.test.*` annotations resolve without extra `-l` flags.

## Alternatives Considered

1. **(β) Two-step with `-library` + `-friend-modules`.** Rejected for Phase D. Requires `doNativeBuild` to produce a `.klib` alongside the `.kexe`, build-state caching updates to track the klib, and path plumbing from build to test. Benefit concentrates in "edit only tests" — not yet established as painful — and the switch is reversible. Deferred to #59.
2. **Compile tests in parallel with `doNativeBuild` in one konanc run.** Rejected: `doBuild` would have to know whether the caller intends to run tests, violating the separation between build and test commands. Also produces a test binary on every `kolt build`.
3. **Parse konanc stdout for pass/fail.** Rejected: konanc's Google Test-style output has no machine-readable format; the exit code is already unambiguous.
4. **Wrapper that translates output to structured format (pass/fail counts).** Rejected as gold-plating for Phase D; can be added later without changing the compile strategy.
5. **Bundle `kotlin.test` via a synthetic `-l`.** Rejected: it is already bundled in konanc. Verified during the Phase D spike: `-generate-test-runner` resolves `kotlin.test.*` imports with no extra flags.

## Related

- ADR 0014 — supersedes this decision; konanc silently no-ops plugin registrars under `-p program`, requiring two-stage library → link for compiler plugin support.
- ADR 0012 — notes that the test path deliberately omits `-e`.
- #16 — Kotlin/Native target support (parent issue)
- PR #58 — Phase D implementation
- #59 — follow-up: incremental test build cache, where the main β argument lives
