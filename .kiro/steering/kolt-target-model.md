# Target Model

kolt's stance on multi-target / KMP support, locked for v1. Background and the
full route comparison live in #411; this document records what is committed.

## Summary

- **v1 build model**: per-project single primary target with `--target=<value>`
  for cross-compile invocation. Cargo / `go build` mental model.
- **kolt is not a KMP-aware build tool at v1.** No `kotlin("multiplatform")`
  plugin equivalent, no source set hierarchy, no `expect` / `actual` machinery.
- **kolt's own source structure is non-KMP forever.** Its multi-target story
  (linuxX64 + macosArm64 at v1) is manual cross-compilation with POSIX function
  availability for OS branching — same model GCC / Cargo expose.
- **`kolt new` presets are single-axis**: `jvm app` / `jvm lib` / `native app` /
  `native lib`. No `multiplatform lib` preset at v1.
- **Ecosystem positioning**: KMP DX is Amper's territory; kolt covers the
  single-target Kotlin niche (server / CLI / lib) where Gradle's overhead and
  KMP's surface area dominate iteration time.
- **Future routes (B1 / B2) are deferred to v2+**, contingent on Kotlin
  compiler API stability and Amper standalone backend maturity.

## Build model: Route A

Single `[build] target = "..."` per project. Cross-compile via
`kolt build --target=<other>` is a v1 first-class feature; multi-target
distribution is a CI concern (matrix-build N artifacts, attach to release).

The five supported native targets at v1 are `linuxX64`, `linuxArm64`,
`macosArm64`, `mingwX64`, and `macosX64` (deprecated). The set is stable for
v1; expansion to iOS / Android Native / watchOS / tvOS is a Phase 2 question.

Per-target dependencies, source set hierarchy, and multi-artifact layout are
out of scope. Projects that need any of those should reach for Gradle KMP or
Amper, not kolt.

## Why not KMP-aware

The strict definition of a KMP project is (1) `kotlin("multiplatform")` plugin,
(2) source set hierarchy, (3) `expect` / `actual`. kolt v1 adopts none of them.
Two reasons drive this:

1. **Compiler API stability.** Driving HMPP-style builds without Gradle
   requires `-Xfragment-refines` / `-Xfragment-sources` and adjacent flags,
   which Kotlin marks internal and changes between releases (HMPP default 1.9+,
   K2 fragment representation 2.0+, klib metadata still evolving). Tracking
   that surface is a continuous tax kolt does not pay back.
2. **Niche split.** JetBrains is investing in Amper as the standalone build
   tool for KMP and Compose Multiplatform — the very use cases that benefit
   from full HMPP. Building a parallel KMP backend in kolt duplicates work
   without serving a distinct user.

If KMP support enters kolt later, the path is most likely to consume Amper's
backend as a library rather than reinvent it. That option opens once Amper's
standalone backend ships and stabilizes; until then, the hold is deliberate.

## v1 commitments

- `kolt.toml` schema: `[build] target = "..."` is single-valued.
- `kolt build --target=<value>` runs cross-compile for any value in the v1
  five-target set.
- `kolt new` presets are the four single-axis combinations
  (`jvm app` / `jvm lib` / `native app` / `native lib`).
- `[targets]` table syntax, per-target dependencies, and `multiplatform lib`
  scaffolding are explicitly NOT shipped in v1.

## Revisit triggers

Reopen the route question when one of these happens:

- Amper ships a stable standalone backend with a public driver API.
- Kotlin promotes the relevant K2 fragment / HMPP flags to non-internal
  status with a stability guarantee.
- Concrete dogfood evidence (multiple users, real projects) shows the
  single-target ceiling is hit by users kolt cares about (not Mobile / Compose
  audiences, where Amper / Gradle KMP are the right tools).
- A specific feature request can only be satisfied by per-target dependencies
  and is large enough on its own to justify B2.

## References

- #411 — the canonical discussion, including the Route A / B1 / B2
  classification and the Amper positioning analysis.
- #412 — `kolt new` 4-preset flow, the first concrete consumer of this stance.
- `project_macos_selfhost_v1.md` (memory) — kolt's own multi-target plan;
  follows the manual cross-compile model described here.
