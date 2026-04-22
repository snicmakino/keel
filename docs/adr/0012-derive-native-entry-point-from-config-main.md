---
status: superseded by ADR-0015
date: 2026-04-13
---

# ADR 0012: Derive the Kotlin/Native entry point from `config.main`

## Summary

- `config.main` (a JVM facade class name) is reused for both targets; no separate `entry_point` field is added. (§1)
- For native builds, kolt derives the entry FQN by stripping the trailing class segment and appending `.main`: `com.example.MainKt` → `com.example.main`. (§1)
- When the trailing segment does not end in `Kt`, kolt emits a pre-build warning. (§2)
- The test path does not call `nativeEntryPoint()` — `-generate-test-runner` synthesises the entry during lowering. (§3)
- **Superseded by ADR 0015**, which inverts the dependency: `config.main` holds the function FQN and kolt derives `MainKt` for JVM instead of the other way around.

## Context and Problem Statement

For `target = "jvm"`, `kolt.toml` requires `main` to be the fully-qualified JVM facade class kotlinc generates from a top-level file:

```toml
main = "com.example.MainKt"
```

The JVM runner passes this verbatim to `java -cp ... <main>`. For `target = "native"`, konanc needs the fully-qualified **function** name — `com.example.main`, not the class. The package prefix is identical; only the trailing segment differs (`MainKt` → `main`). A Phase A spike confirmed the relationship against konanc 2.1.0.

The question was whether `target = "native"` should reuse `config.main` and derive the entry point, or add a separate `native_entry_point` field.

## Decision Drivers

- Single config field for "where my main lives" — no redundant fields to keep in sync
- Existing `kolt.toml` files work for native builds without migration
- Diagnosable failure when the heuristic does not apply

## Decision Outcome

Chosen option: **derive the native entry from `config.main`**, because the package prefix is shared across both backends and the transformation is mechanical for the >95% of projects that follow the standard Kotlin layout.

### §1 Entry point derivation

```kotlin
internal fun nativeEntryPoint(config: KoltConfig): String {
    val lastDot = config.main.lastIndexOf('.')
    val pkg = if (lastDot >= 0) config.main.substring(0, lastDot) else ""
    return if (pkg.isEmpty()) "main" else "$pkg.main"
}
```

Passed to konanc via `-e <derived>`:

```
konanc src -p program -e com.example.main -o build/<name> -l ...
```

Two assumptions: (1) the entry function is named `main`; (2) it lives at the top level of the same package as the JVM facade class.

### §2 Non-`Kt` suffix warning

When `config.main`'s last segment does not end in `Kt`, kolt emits a pre-build warning:

```kotlin
internal fun needsNativeEntryPointWarning(config: KoltConfig): Boolean {
    val lastSegment = config.main.substringAfterLast('.')
    return lastSegment.isNotEmpty() && !lastSegment.endsWith("Kt")
}
```

The warning fires only in `doNativeBuild`; the JVM path uses `config.main` verbatim and is unaffected.

### §3 Test entry point

`kolt test` on native omits `-e` entirely. konanc's `-generate-test-runner` flag synthesises a `main()` during the test-runner lowering pass; passing `-e` in addition would conflict.

### Consequences

**Positive**
- Single source of truth in `kolt.toml`; projects switching between targets do not touch `main`.
- No new config schema; existing `kolt.toml` files work for native builds unchanged.
- Diagnosable failure: `needsNativeEntryPointWarning` fires before the build, followed by konanc's "entry point not found" error if the derivation is wrong.

**Negative**
- Heuristic fragility: a non-`main` entry function name yields a silently wrong FQN and a konanc error.
- No override: users with non-standard layouts cannot specify the exact native FQN.
- The `*Kt` warning is a name-shape check, not a semantic one — `AppKt` with a `fun runApp()` entry gets no warning and fails at konanc.

### Confirmation

`nativeEntryPoint` is a pure function; its contract is unit-tested. The warning path is confirmed by asserting `needsNativeEntryPointWarning` returns `true` for a non-`Kt` suffix.

## Alternatives Considered

1. **Separate `entry_point` field.** Rejected: doubles user surface for the >95% case, creates a redundant field for JVM, and moves the package-knowledge burden onto the user.
2. **Require function FQN for both targets; use `kotlinc -Xmain-function=...` on JVM.** Rejected: breaks every existing `kolt.toml`.
3. **Auto-detect by scanning sources for `fun main()`.** Rejected: adds source-level analysis to a config-driven tool and fails on multiple `main` candidates.
4. **Explicit config for native only, leave JVM implicit.** Rejected: forces extra config on the more cumbersome target while the easier target stays implicit.
5. **Rely on konanc's default entry-point search (no `-e`).** Rejected: konanc defaults to the root package; projects with `fun main()` in a non-root package fail with "Could not find 'main' in '<root>' package". This was the actual error in Phase A's first end-to-end run, which motivated adding `-e`.

## Related

- ADR 0015 — supersedes this decision; `config.main` is redefined as a Kotlin function FQN, and kolt derives `MainKt` for JVM instead.
- #16 — Kotlin/Native target support (parent issue)
- PR #52 — Phase A, where `nativeEntryPoint` was introduced
- PR #58 — Phase D, where the test runner deliberately omits `-e`
