---
status: implemented
date: 2026-04-19
---

# ADR 0023: `target` and `kind` schema for the build axis

## Summary

- `kind` is introduced as a top-level `kolt.toml` field with values `"app"` (default) and `"lib"`. Default `"app"` keeps every existing config parse-compatible. `kind = "lib"` is reserved at schema level but rejected at build time until implemented (§1).
- `target` inside `[build]` switches from the ambiguous `"native"` sentinel to explicit `KonanTarget` identifiers (`"jvm"`, `"linuxX64"`, `"linuxArm64"`, `"macosX64"`, `"macosArm64"`, `"mingwX64"`). `target = "native"` is a hard parse error with a migration hint (§2).
- `[build] target = "X"` (scalar) and `[build.targets.X]` tables are mutually exclusive. The multi-target form is reserved: exactly one `[build.targets.X]` table is accepted (de-sugared to the scalar form internally); two or more produce a "not yet implemented" error (§3).
- Schema placement follows the axis hierarchy: `kind` is project identity (top-level), `target` is a build-axis knob (`[build]`), and per-target overrides live under `[build.targets.X]` (§4).
- The `KonanTarget` vocabulary in `target` matches Gradle Module Metadata naming modulo the known `linuxX64` ↔ `linux_x64` casing difference, handled by a single mapping table in `NativeResolver` (§2).

## Context and Problem Statement

`kolt.toml`'s `target` field accepts `"jvm"` or `"native"`. The `"native"` value collapses every Kotlin/Native triple into one identifier, which works today only because the build pipeline hardcodes `linuxX64` (output paths, libcurl `.def` linker opts, CI). Adding a second native triple — `macosArm64` or `linuxArm64`, both on the README's v1.0 list — forces every additional target into host-detection branches and pushes platform identity out of the config and into runtime inference.

Independently, `kolt.toml` requires `main`, which implicitly commits every project to being an application. There is no way to declare a library, which blocks `kolt publish` and the ADR 0018 self-host endgame.

These two gaps share a schema design surface: a `kind = "lib"` project still needs a `target`, and a `target = "linuxX64"` project still needs a `kind`. Designing them together also means the schema can accommodate a future "KMP non-goal retracted" ADR without a second migration. Whether to retract is out of scope here; whether the schema can absorb the retraction is not.

## Decision Drivers

- `kolt.toml` must be self-describing: artifact identity derives from the config alone, not from host detection.
- Adding a second native target must require no changes to the parser's `when` over target values.
- `kind = "lib"` must be reservable at the schema layer without requiring a full implementation in the same release.
- Breaking changes must be hard errors with migration hints, following the ADR 0015 precedent (no silent two-mode interpretation).
- `target` vocabulary must align with `KonanTarget` identifiers used by Gradle Module Metadata to keep `NativeResolver` mapping code minimal.

## Decision Outcome

Chosen option: introduce `kind` (top-level) and replace `target = "native"` with explicit `KonanTarget` identifiers, with the multi-target table form reserved for a future KMP ADR.

### §1 Introduce `kind`

```toml
# top-level, next to `name` / `version`
kind = "app"   # or "lib"
```

Project-level, immutable across targets. Default `"app"` so every existing `kolt.toml` parses unchanged. `kind = "lib"` is reserved by this ADR but rejected at build time with a "not yet implemented" error; the slot exists so library support is a localized follow-up, not a schema revision.

When `kind = "lib"` is eventually implemented, `[build] main` will be rejected for libraries ("`main` has no meaning for a library; remove it") and `kolt run` against a library will be a usage error. Until then, `kind = "lib"` itself is rejected at parse time, so the `main` rule is reserved schema semantics rather than active validation.

### §2 `target` uses `KonanTarget` identifiers

```toml
[build]
target = "jvm" | "linuxX64" | "linuxArm64"
       | "macosX64" | "macosArm64" | "mingwX64"
```

The native vocabulary matches `KonanTarget.<n>.name`, which is also what Gradle Module Metadata uses (modulo the well-known `linuxX64` ↔ `linux_x64` casing — one mapping table inside `NativeResolver`).

`target = "native"` is removed with no auto-migration. The parser returns:

> `target = "native"` is no longer accepted. Use a specific Konan target, e.g. `target = "linuxX64"`.

This follows ADR 0015's precedent: silent two-mode interpretation is more confusing than a parse-time error, and kolt is pre-1.0.

### §3 `[build] target = "X"` and `[build.targets.X]` are mutually exclusive

```toml
# Single-target form
[build]
target = "linuxX64"

# Multi-target form (reserved)
[build]
# shared fields only — no `target` scalar

[build.targets.jvm]
[build.targets.linuxX64]
```

Specifying both a scalar `target` in `[build]` and one or more `[build.targets.X]` tables is a parse error. There is no `target = "kmp"` sentinel — the existence of two or more `[build.targets.X]` tables is itself the multi-target signal.

Multi-target form is reserved by this ADR: parsing accepts exactly one `[build.targets.X]` table (de-sugared to `[build] target = "X"` internally); two or more produce a "multi-target builds are not yet implemented" error. This is the surface a future KMP ADR would build on.

Empty `[build.targets.X]` tables are legal — their presence is the declaration. The per-target field vocabulary (which existing `[build]` fields may be overridden inside a `[build.targets.X]` table) is deferred to the KMP follow-on ADR.

### §4 Schema placement

| Field | Location | Rationale |
| :--- | :--- | :--- |
| `kind` | top-level | Project identity (next to `name`, `version`), invariant across targets. |
| `target` (scalar) | inside `[build]` | Unchanged location from the current schema — only the accepted values change. |
| `[build.targets.X]` tables | nested under `[build]` | Multi-target is a refinement of the build axis; nesting makes the containment explicit and keeps shared fields in `[build]` directly. |

### Consequences

**Positive**
- `kolt.toml` becomes self-describing: what artifact a build produces is determined by the config alone, not by host detection. ADR 0018's tarball naming becomes a function of `target`, not of CI runner identity.
- Library packaging is unblocked at the schema level. The slot to declare a library exists; the implementation follow-up changes the build pipeline without revisiting the parser.
- A future ADR retracting the KMP non-goal needs only to lift the "one `[build.targets.X]` only" restriction and define per-source-set semantics; no schema redesign.
- Each legal `target` value names a real, distinct artifact shape. The parser's `when` over targets stays meaningful.

**Negative**
- Two breaking changes ship in one release. `target = "native"` rejection has no compatibility shim; the `kind` default mitigates the second change. Acceptable pre-1.0; called out in the release notes.
- Both `kind = "lib"` and multi-target form land as explicit rejection messages. The error text must make it unambiguous that this is intentional reservation, not a bug.
- Once `target = "macosArm64"` is a writable value, users will expect cross-compilation from any host. konanc supports this, but cinterop `.def` files will need per-target keys (`compilerOpts.osx`, `compilerOpts.mingw`) — documentation work, not code work.

### Confirmation

Schema validation in `KoltTomlParser`; rejected values surface as parse-error test cases in `KoltTomlParserTest.kt`. ADR text and parser stay in sync via PR review.

## Alternatives considered

1. **Keep `target = "native"` and auto-detect the host.** Rejected. The same `kolt.toml` would produce different artifacts depending on who runs `kolt build`. ADR 0015 rejected the same shape for the same reason.
2. **Introduce `target = "kmp"` as a sentinel.** Rejected. The presence of two or more `[targets.X]` tables is itself the signal; a sentinel would be redundant and would create a "what wins when both are declared" ambiguity. Cargo and Amper both omit the equivalent sentinel.
3. **Fold `kind` into `target` (`target = "jvm-lib"` etc.).** Rejected. Conflates orthogonal axes and explodes the vocabulary multiplicatively (six targets × two kinds = twelve values instead of six plus two).
4. **Place `kind` inside `[build]`.** Rejected. `kind` is project identity, invariant across targets — it belongs next to `name` and `version`, not with build-axis knobs. A future multi-target `kolt.toml` will carry multiple `[build.targets.X]` tables sharing one `kind`; nesting it inside `[build]` would invite the same "which target's kind wins" ambiguity that disqualifies `target = "kmp"` above.

## Related

- #167 — tracking issue (parser, validation, migration, `kolt init`, resolver dispatch update)
- ADR 0010 — Gradle Module Metadata for native resolution (consumes the Konan target identifier)
- ADR 0015 — `main` is a Kotlin function FQN (precedent for hard-error migration with a hint)
- ADR 0018 — Distribution layout (its tarball naming becomes a function of the `target` value chosen here)
- README — `Not yet supported` list (`macOS and linuxArm64 targets` becomes a target-vocabulary question after this ADR)
- `architecture.md` — `Out of scope` section's KMP entry (the follow-on ADR will revisit; this ADR does not)
