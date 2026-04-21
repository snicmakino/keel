# Brief: lib-build-pipeline

Tracks GitHub issue #30. Milestone: v1.0. Size: L.

## Problem

`kind = "lib"` is reserved at the schema level (ADR 0023, PR #172) but rejected at
parse time with "not yet implemented". Users cannot express library projects today,
which blocks:

- `kolt publish` (#21) — a library is the thing publishing makes sense for.
- ADR 0018 self-host endgame — the long-term plan is for `kolt-compiler-daemon/`
  to be a kolt-built library, not a Gradle subproject.

## Current State

- **Parser**: `validateKind` in `src/nativeMain/kotlin/kolt/config/Config.kt:128-140`
  explicitly errors on `kind = "lib"` with "reserved but not yet implemented (ADR
  0023)".
- **`[build] main`**: declared non-nullable in `RawBuildSection.main`
  (`Config.kt:106`), so ktoml deserialization fails if it is missing. FQN format is
  further checked by `validateMainFqn` (`Config.kt:186`).
- **Build pipeline (Native)**: `Builder.kt:68-124` always runs both stages —
  `nativeLibraryCommand` (stage 1, `.klib`) followed by `nativeLinkCommand` (stage 2,
  `.kexe`). There is no path that stops after stage 1.
- **Build pipeline (JVM)**: `jarCommand` (`Builder.kt:221-227`) already produces a
  thin jar via plain `jar cf`. There is no Main-Class manifest today, but the jar
  contents are produced on the assumption that `main` exists.
- **`kolt run`**: `doRun` (`BuildCommands.kt:406-435`) assumes an executable exists
  (resolves `outputKexePath` for native, derives `jvmMainClass` for JVM). No
  kind-aware gate.
- **Tests**: `ConfigTest.kt:311-336` pins the current "not yet implemented" rejection
  — this test must be replaced, not just deleted.

## Desired Outcome

A kolt.toml with `kind = "lib"` parses, builds, and produces a library artifact
on both JVM and Native targets. Running a library fails fast with a clear error.

- Parser accepts `kind = "lib"`.
- `[build] main` is required iff `kind = "app"`, and forbidden when `kind = "lib"`
  with the ADR 0023 §1 error: `main has no meaning for a library; remove it`.
- `kolt build` for `kind = "lib"`:
  - **JVM**: thin `.jar` (no `-include-runtime`, no Main-Class attribute).
  - **Native**: stops after stage 1 (`.klib`), no `.kexe` link.
- `kolt run` against `kind = "lib"` errors with `library projects cannot be run`.

## Approach

Minimal, surgical edits along the existing responsibility seams. No new modules.

1. **Parser** (`kolt.config`):
   - Change `RawBuildSection.main` to nullable.
   - Replace `validateKind`'s lib-rejection with acceptance.
   - Add conditional validation: `main` required for app, forbidden for lib, with
     the exact ADR 0023 §1 error text.
   - Carry `kind` through to the parsed `Config` (already present per ADR 0023).
2. **Build pipeline** (`kolt.build.Builder`):
   - Branch on `config.kind` before invoking stage 2 on native (`nativeLinkCommand`
     is skipped for lib).
   - For JVM, the current thin-jar output already matches lib semantics; assert
     no Main-Class for lib and leave app-side path undisturbed.
3. **CLI guard** (`kolt.cli.BuildCommands.doRun`):
   - Check `config.kind == "lib"` at the top of `doRun`, return the rejection
     error before any path resolution.
4. **Tests**:
   - Replace `kindLibIsRejectedAsNotYetImplemented` with `kindLibIsAccepted` plus
     coverage for the conditional-`main` matrix (app+main OK / app+no-main err /
     lib+no-main OK / lib+main err).
   - Add build-pipeline tests: native lib stops after `.klib`; JVM lib produces
     thin jar without Main-Class; `kolt run` on lib errors.

## Scope

- **In**:
  - Parser: `kind = "lib"` accept + `main` conditional validation.
  - JVM build: thin-jar path for lib (no Main-Class, no `-include-runtime`).
  - Native build: klib-only path for lib (skip stage 2 link).
  - CLI: `kolt run` rejection for lib.

- **Out (tracked as separate issues)**:
  - `kolt publish` (#21) — consumes this spec's output.
  - `fat_jar = false` for `kind = "app"` — orthogonal thin-app variant.
  - `kolt new --lib` scaffolding (#28).

## Boundary Candidates

- **Config parser** (`kolt.config`): kind + conditional main validation.
- **Build pipeline** (`kolt.build.Builder`): kind-aware stage 2 / manifest skip.
- **CLI guard** (`kolt.cli`): run-on-lib rejection.

These three seams are already separated in the codebase (see structure.md) and can
be implemented and reviewed as independent tasks within the same spec.

## Out of Boundary

- Publishing mechanics, POM/metadata generation, Maven coordinate derivation — all
  belong to `kolt publish` (#21).
- Making `fat_jar` configurable for apps — separate axis from kind.
- Scaffolding (`kolt new --lib`) — separate UX concern.
- Changing the existing native two-stage flow itself — ADR 0014 is load-bearing and
  out of scope; this spec only chooses when to stop.

## Upstream / Downstream

- **Upstream**:
  - ADR 0023 §1 (target/kind schema, merged via #167/#172).
  - ADR 0014 (native two-stage library+link) — the `.klib` stage already exists.
  - ADR 0018 (distribution / self-host path) — motivates the work but is not a
    prerequisite.
- **Downstream**:
  - #21 `kolt publish` — needs lib artifacts to publish.
  - ADR 0018 follow-through: migrating `kolt-compiler-daemon/` to a kolt-built lib.
  - #28 `kolt new --lib` — depends on a working lib path.

## Existing Spec Touchpoints

- **Extends**: none (no prior specs exist; this is the first).
- **Adjacent**: none currently. Future `kolt-publish` spec will sit immediately
  downstream and must not redefine the lib artifact contract set here.

## Constraints

- **No pre-v1 migration shims**: v0.13.0 is pre-v1, breaking `kolt.toml` shape
  changes are permitted (`main` becoming optional is not a break — existing app
  configs keep working).
- **ADR 0023 §1 error text is canonical**: the "`main` has no meaning for a
  library; remove it" phrasing is specified by the ADR; don't paraphrase.
- **Native toolchain**: klib-only requires no new konanc flags — stage 1
  (`-p library -nopack`) already produces the artifact. Verify with a real
  `./gradlew check` after implementation.
- **Kotlin version**: 2.3.x (per steering/tech.md); no version bump needed.
