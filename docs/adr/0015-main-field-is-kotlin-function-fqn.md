---
status: accepted
date: 2026-04-13
---

# ADR 0015: `main` field is a Kotlin function FQN

## Summary

- `config.main` holds a Kotlin top-level function FQN (e.g. `com.example.main`), not a JVM class name. (§1)
- `validateMainFqn` rejects values ending in `Kt` at parse time with a migration hint; there is no migration period. (§2)
- JVM builds derive `MainKt` from the function FQN via `jvmMainClass()`; native builds forward `config.main` to konanc verbatim via `-e`. (§3)
- `nativeEntryPoint()` / `needsNativeEntryPointWarning()` (ADR 0012) are deleted; `nativeLinkCommand` now emits `-e config.main` directly. (§4)
- `kolt init` generates `main = "main"` instead of `main = "MainKt"`. (§5)

## Context and Problem Statement

ADR 0012 defined `config.main` as a JVM facade class name (`com.example.MainKt`) and derived the native entry FQN from it. Two problems surfaced once `target = "native"` became a first-class path:

1. **Leaky abstraction.** `MainKt` is a JVM implementation artifact — kotlinc synthesises it from the file name, and it has no meaning outside the JVM. Forcing users to write a JVM class name in a target-agnostic config field exposes a JVM-internal concept.
2. **Field meaning diverges from effect.** On the native path, kolt silently ignored the `Kt` suffix and derived the function FQN. `config.main = "com.example.MainKt"` did not mean "this is my entry" for konanc — it was a seed for a heuristic.

The self-host effort (#61) triggered the concrete bug: kolt's own `fun kolt.cli.main()` lives in a named package. ADR 0014's `nativeLinkCommand` intentionally omitted `-e`, assuming `-Xinclude` would carry the entry point. konanc failed with `could not find '/main' function` because it searched the root package. The `-Xinclude` assumption holds only when `fun main()` is at the root package.

## Decision Drivers

- `config.main` is target-agnostic — the same `kolt.toml` works for both JVM and native
- Users express their intent in Kotlin terms (function FQN), not JVM-backend terms
- Hard error at parse time for old-style values; no silent two-mode interpretation
- `nativeEntryPoint()` heuristic eliminated from the compile path

## Decision Outcome

Chosen option: **function FQN as the canonical `main` value**, because it matches the native backend directly and lets kolt derive the JVM class name mechanically.

| `main` value | JVM Main-Class | Native `-e` |
| :--- | :--- | :--- |
| `main` | `MainKt` | `main` |
| `com.example.main` | `com.example.MainKt` | `com.example.main` |

### §1 Semantic change

`config.main` holds the fully-qualified name of a Kotlin top-level `fun main()`. It is target-independent; each backend derives what it needs from the same value.

### §2 Parsing and validation

`parseConfig` calls `validateMainFqn` after schema decoding:

- Accepts the bare literal `"main"`, or any dotted package followed by `".main"`.
- Rejects any value ending in `Kt` with a migration hint back-deriving the function FQN: `main = "com.example.MainKt"` → `Use a Kotlin function FQN instead: main = "com.example.main"`.
- Rejects other malformed values with a generic error.

No migration period. kolt is pre-1.0 (v0.9.0) and the two meanings cannot coexist — a hard error at parse time is less confusing than silent dual-mode interpretation.

### §3 Per-backend derivation

**JVM.** `jvmMainClass(main)` replaces the final `main` segment with `MainKt`:

```kotlin
fun jvmMainClass(main: String): String {
    val prefix = main.substringBeforeLast("main")
    return "${prefix}MainKt"
}
```

This is a best-effort heuristic: it assumes `fun main()` lives in a file named `Main.kt`, which is `kolt init`'s convention. Projects that place `fun main()` in a file with a different name compile to `<FileName>Kt`; `jvmMainClass()` will point at the wrong class. Out of scope until needed — see §6.

**Native.** `nativeLinkCommand` forwards `config.main` verbatim to konanc via `-e`:

```kotlin
add("-e")
add(config.main)
```

No derivation required — the function FQN is exactly what konanc expects.

### §4 Deletions

`nativeEntryPoint()` and `needsNativeEntryPointWarning()` (ADR 0012) are deleted. The comment on `nativeLinkCommand` warning against `-e` is removed — `-e config.main` is now load-bearing.

### §5 `kolt init` template

`kolt init` generates `main = "main"` (bare function name) instead of `main = "MainKt"`.

### §6 Non-goal: `@JvmName` overrides

Projects that override the JVM class name via `@JvmName("Foo")` or place `fun main()` in a non-`Main.kt` file produce a JVM class `jvmMainClass()` cannot recover from `main` alone. Deliberately out of scope (YAGNI). The eventual fix is an explicit `jvm_main_class` override in `kolt.toml`, unused for `target = "native"`.

### Consequences

**Positive**
- `config.main` is target-agnostic: the same `kolt.toml` works for both targets without touching the field.
- `nativeEntryPoint()` heuristic is eliminated; the compile path is simpler and direct.
- Users migrating from the old scheme see a precise parse-time error with an exact replacement.

**Negative**
- `jvmMainClass()` is a best-effort heuristic that fails for non-`Main.kt` entry files.
- No override until `jvm_main_class` is added to the schema.

### Confirmation

`parseConfig` tests: a value ending in `Kt` returns `Err(ConfigError.InvalidMain(...))` with the migration hint. A valid function FQN returns `Ok`. `jvmMainClass("com.example.main")` returns `"com.example.MainKt"`.

## Related

- ADR 0012 — superseded by this decision; the `nativeEntryPoint()` heuristic is deleted.
- ADR 0014 — `nativeLinkCommand` note updated: `-e config.main` is now emitted directly.
- #61 — self-host work, where the `nativeLinkCommand` `-Xinclude` assumption broke on `kolt.cli.main`
- `src/nativeMain/kotlin/kolt/config/Main.kt` — `jvmMainClass`, `validateMainFqn`
- `src/nativeMain/kotlin/kolt/build/Builder.kt` — `nativeLinkCommand` emits `-e config.main`
- `src/nativeMain/kotlin/kolt/build/Runner.kt` — JVM `runCommand` uses `jvmMainClass(config.main)`
