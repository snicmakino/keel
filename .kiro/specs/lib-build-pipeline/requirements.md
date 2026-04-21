# Requirements Document

## Introduction

Tracks GitHub issue #30 (milestone v1.0, size L). ADR 0023 §1 reserved
`kind = "lib"` at the schema level, but the parser still rejects it with a
"not yet implemented" error and the build pipeline assumes an application
entry point on every path. This spec lifts the placeholder rejection, makes
`[build] main` conditional on `kind`, and threads the library path through
the JVM build (thin jar), the native build (`.klib`-only, stopping before
ADR 0014's link stage), and `kolt run` (rejection). It unblocks `kolt
publish` (#21) and the ADR 0018 self-host endgame where
`kolt-compiler-daemon/` becomes a kolt-built library rather than a Gradle
subproject.

## Boundary Context

- **In scope**: config parsing for `kind = "lib"` and conditional `[build] main`
  validation; JVM library build output (thin jar, no Main-Class); native
  library build output (`.klib` only, no `.kexe`); `kolt run` rejection for
  libraries; non-regression for every existing `kind = "app"` behavior;
  continued testability of library projects via `kolt test`.
- **Out of scope**: `kolt publish` (#21) and any publish metadata / POM
  generation; a thin-jar variant for `kind = "app"` (orthogonal `fat_jar`
  axis); `kolt new --lib` scaffolding (#28); the semantics of consuming a
  kolt-built library from another kolt project (covered by the downstream
  publish spec, not here).
- **Adjacent expectations**: ADR 0023 §1 is authoritative for the `main`-on-
  library error wording; ADR 0014's native two-stage library+link flow
  remains unchanged — this spec only chooses when to stop; ADR 0013's
  native test flow remains unchanged — tests stay runnable for libraries.

## Requirements

### Requirement 1: Config parser accepts `kind = "lib"` with conditional `main`

**Objective:** As a kolt user, I want to declare `kind = "lib"` in
`kolt.toml` without providing `[build] main`, so that I can express a
library project that has no application entry point.

#### Acceptance Criteria

1. When `kolt.toml` declares `kind = "lib"` without `[build] main`, the kolt
   config parser shall parse the config successfully.
2. If `kolt.toml` declares `kind = "lib"` together with `[build] main`, the
   kolt config parser shall reject the config with an error whose message
   contains `main has no meaning for a library; remove it`.
3. When `kolt.toml` declares `kind = "app"` without `[build] main`, the kolt
   config parser shall reject the config with a missing-`main` error.
4. When `kolt.toml` declares `kind = "app"` together with `[build] main`,
   the kolt config parser shall parse the config successfully.
5. When `kolt.toml` omits `kind`, the kolt config parser shall default `kind`
   to `"app"` and apply the `kind = "app"` rules above.

### Requirement 2: JVM library build produces a thin jar

**Objective:** As a kolt user of a JVM library project, I want `kolt build`
to produce a plain class jar, so that the artifact can later be published
and consumed as a Maven-compatible library.

#### Acceptance Criteria

1. When `kolt build` runs against a `kind = "lib"` config targeting the JVM,
   the kolt build pipeline shall produce a `.jar` in the project's build
   output directory containing the project's compiled classes.
2. The kolt build pipeline shall produce a `kind = "lib"` JVM jar that does
   not bundle the Kotlin standard library or any resolved dependency
   classes.
3. The kolt build pipeline shall produce a `kind = "lib"` JVM jar that does
   not declare a `Main-Class` manifest attribute.
4. When `kolt build` runs against a `kind = "app"` config targeting the JVM,
   the kolt build pipeline shall produce the same jar shape and manifest
   entries as it does today.

### Requirement 3: Native library build stops at the `.klib` stage

**Objective:** As a kolt user of a native library project, I want
`kolt build` to stop after producing a `.klib`, so that no executable is
linked from a library that has no entry point.

#### Acceptance Criteria

1. When `kolt build` runs against a `kind = "lib"` config targeting a native
   platform, the kolt build pipeline shall produce a `.klib` artifact in the
   project's build output directory.
2. The kolt build pipeline shall not produce a `.kexe` artifact for a
   `kind = "lib"` native project.
3. The kolt build pipeline shall not invoke a native link step for a
   `kind = "lib"` native project.
4. When `kolt build` runs against a `kind = "app"` config targeting a native
   platform, the kolt build pipeline shall continue to produce both the
   `.klib` and the `.kexe` as it does today.

### Requirement 4: `kolt run` rejects library projects

**Objective:** As a kolt user, I want `kolt run` against a library project
to fail fast with a clear message, so that I am not left debugging a missing
executable.

#### Acceptance Criteria

1. If `kolt run` is invoked against a `kind = "lib"` config, the kolt CLI
   shall exit with a non-zero status and emit an error whose message
   contains `library projects cannot be run`.
2. The kolt CLI shall emit the library-run rejection before attempting to
   resolve a runnable artifact and before invoking the build pipeline.
3. The kolt CLI shall apply the library-run rejection regardless of whether
   the library targets the JVM or a native platform.
4. When `kolt run` is invoked against a `kind = "app"` config, the kolt CLI
   shall execute the existing run flow unchanged.

### Requirement 5: `kolt test` continues to work for library projects

**Objective:** As a kolt user of a library project, I want `kolt test` to
compile and run tests, so that a library without `[build] main` remains
testable.

#### Acceptance Criteria

1. When `kolt test` runs against a `kind = "lib"` config, the kolt test
   pipeline shall compile the project's tests and execute them.
2. The kolt test pipeline shall not require `[build] main` to be present
   when `kind = "lib"`.
3. When `kolt test` runs against a `kind = "app"` config, the kolt test
   pipeline shall continue to work unchanged.
