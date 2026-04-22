---
status: accepted
date: 2026-04-11
---

# ADR 0009: Auto-install managed toolchains instead of falling back to system

## Summary

- `kolt.toml` is the single source of truth for compiler and JDK versions. If the managed toolchain for the declared version is absent, kolt downloads and installs it automatically before proceeding. (§1)
- The resolve→install→resolve pattern is applied via `ensureKotlincBin` and `ensureJdkBins` in `ToolchainManager.kt`, reusing the shape already in use for `ktfmt` and the JUnit Console Launcher. (§2)
- `checkVersion()` and all system-fallback paths are deleted. (§3)
- Downloads are SHA-256 verified before installation, inheriting the check already in place for explicit `kolt toolchain install`. (§4)
- `kolt toolchain install` remains available as an explicit pre-install step; auto-install is idempotent alongside it. (§5)

## Context and Problem Statement

Before Issue #40, kolt had two parallel toolchain strategies. The managed path downloaded a specific kotlinc/JDK version to `~/.kolt/toolchains/` via `kolt toolchain install` (landed in PR #42 and #44). If the managed binary was absent, kolt fell through to whatever `kotlinc` was on `PATH` and emitted a version-mismatch warning that users learned to ignore.

The result was that `kolt.toml`'s `kotlinc_version` field was not authoritative. A project declaring `kotlinc_version = "2.3.20"` might compile with 2.0.0 on one machine and 2.3.20 on another, with no reliable detection. The first `kolt build` on a fresh clone also failed with a cryptic "no kotlinc on PATH" error unless the user knew to pre-run `kolt toolchain install`.

## Decision Drivers

- The version in `kolt.toml` must control which compiler actually runs on every machine.
- `git clone && kolt build` must succeed on a clean machine, assuming network access.
- No silent version mismatch between declared and actual compiler.
- Reuse existing toolchain code paths; no parallel mechanism.

## Decision Outcome

Chosen option: **auto-install**, because it makes `kolt.toml` the sole runtime decision point for compiler and JDK selection.

### §1 `kolt.toml` as authoritative toolchain spec

The version declared in `[build] kotlinc_version` and `[build] jdk` is what runs. If the managed toolchain for that version is absent from `~/.kolt/toolchains/`, kolt downloads and installs it synchronously before any compile step. There is no fallback to system binaries.

### §2 resolve → install → resolve pattern

`ToolchainManager.kt` adds:

- `ensureKotlincBin(version, paths, exitCode)`: calls `resolveKotlincPath`; on miss, calls `installKotlincToolchain`; re-resolves; hard-fails if the binary is still absent.
- `ensureJdkBins(version, paths, exitCode)`: same for `java` and `jar`, returning a `JdkBins(java, jar)` record.

`BuildCommands.kt` entry points (`doBuild`, `doCheck`, `doTest`, …) call these instead of the old `resolveKotlincPath` / `resolveJdkBins`. `JdkBins` moves from `BuildCommands` to `ToolchainManager`.

### §3 Removal of system-fallback paths

`checkVersion()` is deleted — there is nothing to warn about because the managed toolchain version is guaranteed to match `kolt.toml`. The "falling back to system" branch and its path-probing code are removed. Dead-code cleanup followed in PR #47.

### §4 SHA-256 verification

`installJdkToolchain` and `installKotlincToolchain` verify downloads before installation (per `4b3fe4f`). Auto-install inherits this check.

### §5 `kolt toolchain install` preserved

Users who want to pre-populate `~/.kolt/toolchains/` for CI timing predictability or offline use can still run `kolt toolchain install` explicitly. Auto-install is idempotent: the `ensure*` functions skip to the re-resolve step when the toolchain directory already exists.

### Consequences

**Positive**
- `kolt.toml` controls the exact compiler on every machine; CI, local, and collaborators' machines produce compiler-identical output.
- `git clone && kolt build` works on a clean machine with no manual setup step.
- `checkVersion` warning is gone — there is no mismatch to warn about.
- One idiom — resolve→install→resolve — now covers kotlinc, JDK, ktfmt, and the JUnit Console Launcher uniformly.

**Negative**
- First build blocks on a download: an unseen kotlinc + JDK pair can be 150 MB on a slow connection.
- Offline first-run is impossible: without network access, a user cannot build until they reach GitHub (kotlinc) or the JDK mirror. Pre-populating `~/.kolt/toolchains/` from a networked machine is the workaround.
- Hard-coded download sources: a URL change or outage breaks every first build until the source is updated.
- The `ensure*` helpers call `exitProcess(exitCode)` on unrecoverable download failure, bypassing `Result`-based error handling (ADR 0001). A future refactor may thread `Result<String, ToolchainError>` through `ensure*` instead.

### Confirmation

PR review confirms system-fallback code is absent and `ensureKotlincBin` / `ensureJdkBins` are the only toolchain resolution paths in `BuildCommands.kt`.

## Alternatives considered

1. **Keep system fallback, make version mismatch a hard error.** Rejected — users with the wrong system version get a confusing failure instead of a successful build. kolt already knows how to install the right version.
2. **Auto-install only when no kotlinc is present, keep fallback if any kotlinc exists.** Rejected — a mismatched system kotlinc would still silently succeed on machines that happened to have any kotlinc; behaviour would differ by environment.
3. **Prompt the user interactively before downloading.** Rejected — blocks non-interactive contexts (CI, scripts). The version is already committed to the repo, so consent is implicit.
4. **Bundle kotlinc/JDK in the kolt binary.** Rejected — inflates the binary by 150+ MB, bakes in one compiler version, and defeats the "whatever `kolt.toml` says" principle.

## Related

- `src/nativeMain/kotlin/kolt/tool/ToolchainManager.kt` — `ensureKotlincBin`, `ensureJdkBins`, `installKotlincToolchain`, `installJdkToolchain`
- `src/nativeMain/kotlin/kolt/tool/ToolManager.kt` — the `ensureTool` template
- Commit `0daa432` / PR #46 — auto-install switch-over
- Commit `7d544bb` / PR #47 — dead-code cleanup
- Commit `4b3fe4f` — SHA-256 verification for JDK downloads
- ADR 0001 — `ensure*` helpers call `exitProcess` on unrecoverable failure, a carve-out from the `Result` rule
