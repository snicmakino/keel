---
status: implemented
date: 2026-04-08
---

# ADR 0001: Use kotlin-result `Result<V, E>` for all error handling

## Summary

- Every side-effectful function returns `Result<V, E>` from `kotlin-result` 2.3.1; pure functions are exempt. (§1)
- Each function declares its own error type — no global `KoltError`. Sealed classes group meaningful variants (`ProcessError`); independent errors stay as data classes (`OpenFailed`, `MkdirFailed`). (§2)
- Consumers unwrap with `getOrElse { error -> ... }` and exhaust with `when`; the compiler forces every variant to be handled. (§3)
- `ConfigParseException`, the `-1` sentinel in `executeCommand`, and nullable error returns are deleted in favour of explicit `Result` types. (§4)
- `ExitCode.kt` plus per-command mapping translates every `Result<_, E>` into a deterministic CLI exit code; users never see a Kotlin stack trace. (§5)

## Context and Problem Statement

The Phase 1 prototype mixed three error styles on the same call path: exceptions (`ConfigParseException` from `parseConfig`), sentinel exit codes (`executeCommand` returning `-1` for "failed to start"), and nullable returns (`readFileAsString` collapsing "missing", "permission denied", and "I/O error" into one `null`). `Main.kt` combined `try/catch`, `if (result == -1)`, and `?: run { ... }` in sequence, and adding a failure mode meant auditing every caller.

Kotlin/Native exceptions are also a poor fit: stack traces are expensive, `CancellationException` semantics diverge from JVM, and exceptions interact poorly with `cinterop` boundaries. The project charter prohibits throwing for these reasons. We need a discipline that puts every failure mode in the signature, forces exhaustive handling at the call site, and works without exceptions or reflection on Kotlin/Native linuxX64.

## Decision Drivers

- Failure modes must be visible in the function signature.
- The call site must be forced to handle every variant.
- No exceptions across `cinterop` or `fork`/`execvp` boundaries.
- No JVM-only libraries — must work on Kotlin/Native linuxX64.
- Every `kolt build` failure must map to a known exit code and known message.

## Decision Outcome

Chosen library: **`kotlin-result` 2.3.1**, because it ships a single Kotlin/Native-compatible klib with no transitive dependencies and satisfies every driver above.

### §1 `Result` is the return type for every side-effectful function

Pure helpers (value-in / value-out, no I/O — `Builder.buildCommand`, `VersionCompare.compare`, …) keep plain return types. Every function that touches the filesystem, a subprocess, the network, or fallible decoding returns `Result<V, E>`. Canonical signatures from Phase 1:

```
parseConfig()       -> Result<KoltConfig, ConfigError>
readFileAsString()  -> Result<String, OpenFailed>
ensureDirectory()   -> Result<Unit, MkdirFailed>
executeCommand()    -> Result<Int, ProcessError>
executeAndCapture() -> Result<String, ProcessError>
```

New subsystems follow the pattern: `ResolveError`, `LockfileError`, `DownloadError`, `ToolchainError`, etc.

### §2 Per-subsystem error types, no global hierarchy

Each function declares the smallest error type that fits its actual failure modes. Variants that share a meaningful parent become a sealed class (`ProcessError` covers `EmptyArgs`, `ForkFailed`, `WaitFailed`, `NonZeroExit`, `SignalKilled`). Independent errors stay as plain data classes. A global `KoltError` was rejected (see Alternatives) because it defeats signature precision.

### §3 Exhaustive handling at every call site

Consumers unwrap with `getOrElse { error -> ... }` and match with an exhaustive `when` over the sealed hierarchy. Adding a new variant breaks every call site that does not handle it — this has already caught real bugs during refactors (ToolchainError expansion, native resolve error additions).

### §4 No exceptions, no sentinels

`ConfigParseException` is deleted; `parseConfig` returns `Result<KoltConfig, ConfigError>`. The `-1` exit-code sentinel in `executeCommand` is gone — start failures are `ProcessError.StartFailed`, and the `Int` in `Result<Int, ProcessError>` is the actual child exit code. Nullable error returns are replaced with `Result<T, *Error>`. Bridging third-party APIs that still throw (e.g. ktoml) requires `runCatching { ... }.mapError { ... }` at the boundary.

### §5 Deterministic CLI exit codes

`ExitCode.kt` plus per-command error mapping is the single place that translates `Result<_, E>` into a numeric exit code. Stack traces never reach the user.

### Consequences

**Positive**
- Signatures list every failure mode; new ones are type-level changes that break every call site.
- Exhaustive `when` over sealed hierarchies turns missed-variant bugs into compile errors.
- Exceptions never propagate through `cinterop` or `fork`/`execvp` boundaries.
- `andThen`/`map`/`mapError`/`flatMap` keep orchestration code top-to-bottom.
- Single, predictable CLI exit-code surface.

**Negative**
- Every signature gains a `Result<_, _>` wrapper and an explicit error type — more verbose than `throws`.
- A dozen-plus `*Error` types now exist; choosing whether a new failure belongs in an existing type or a new one is a judgement call.
- Throwing third-party APIs need `runCatching` wrappers at the boundary.
- Stack traces are lost — recovering call context means threading a `cause` field manually.

### Confirmation

Enforced by review and by the `CLAUDE.md` rule ("Exception throwing is prohibited — use kotlin-result `Result<V, E>` for all error handling"). A PR adding `throw` outside a `runCatching` boundary is rejected.

## Alternatives considered

1. **Checked exceptions via `@Throws`.** Rejected — `@Throws` is only enforced on the JVM for Java interop; on Kotlin call sites it is documentation, not a compile error.
2. **A single top-level `KoltError` sealed class.** Rejected — every function would advertise it could return any error, defeating precise signatures. Errors must be local to the subsystem.
3. **Arrow `Either<E, V>`.** Rejected — Arrow's footprint (arrow-core, arrow-fx-coroutines, kotlinx-coroutines) is far larger than kotlin-result for marginal ergonomic gain in a fast-startup native binary.
4. **Nullable returns plus a separate "last-error" field.** Rejected — stateful error reporting is exactly the pattern this ADR leaves behind, and it does not compose.

## Related

- `CLAUDE.md` — project rule prohibiting exception throwing
- ADR 0002 — `ProcessError` is the surface this ADR formalises
- Commit `670e5c3` — initial Phase 1 migration to `Result`
