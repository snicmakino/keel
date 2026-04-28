---
status: implemented
date: 2026-04-28
---

# ADR 0030: Build profiles

## Summary

- kolt accepts a Cargo-style `--release` opt-in flag on the four
  build-driving commands (`build`, `test`, `run`, `check`); the default
  remains the debug profile, and the active profile is determined solely
  from the command line — `kolt.toml` carries no profile section (§1).
- On Native targets, the link stage routes `-opt` if and only if the
  profile is Release and `-g` if and only if the profile is Debug; the
  klib library stage stays profile-agnostic. Native artifact output and
  the project-local Native incremental-compile cache partition into
  `build/<profile>/` and `build/<profile>/.ic-cache` respectively (§2).
- On JVM targets `--release` is a declared no-op: identical kotlinc
  arguments, identical `~/.kolt/daemon/ic/<v>/<id>/` path, and identical
  compile output bytes modulo file-system timestamps. The JVM jar still
  partitions under `build/<profile>/<name>.jar` so a flip between
  profiles cannot overwrite either artifact (§3).
- `scripts/assemble-dist.sh` invokes `kolt build --release` for every
  sub-build and reads the root binary from `build/release/kolt.kexe`;
  distribution tarballs are unambiguously release-built (§4).
- Issue #261 specified "daemon IC store under
  `~/.kolt/daemon/ic/<v>/<id>/<profile>/`". That schema was a
  misattribution: `~/.kolt/daemon/ic/` is JVM-daemon-owned (ADR 0019 §5)
  and JVM is no-op, while the project-local Native IC cache lives at
  `build/<profile>/.ic-cache`. The shipped layout matches the latter; no
  daemon IC path gains a profile segment (§5).
- Pre-v1 policy applies: stale `build/.ic-cache` from earlier kolt
  versions is abandoned. The release note instructs `rm -rf build/` for
  users who want a clean slate; no migration shim ships (§6).

## Context and Problem Statement

`kolt build` currently compiles Native targets with a single profile-less
konanc invocation. Daily Native iteration loops pay the optimization-class
link time even when no optimization is needed, and distribution binaries
assembled by `scripts/assemble-dist.sh` are not explicitly marked as
release-built. With no profile concept kolt also looks incomplete next to
Cargo / Gradle / Bazel and blocks the planned Gradle-removal direction
and the `curl | sh` installer (#230).

This ADR records the decision to introduce an opt-in `--release` profile
without adding `kolt.toml` configuration sections, and to declare the flag
a no-op on JVM where there is no comparable cost to optimize away.

## Decision Drivers

- Daily Native iteration must not pay optimization-class link time by
  default.
- Distribution binaries must be unambiguously release-built so the
  upcoming `curl | sh` installer (#230) ships optimized artifacts.
- The JVM build path must keep observable behaviour stable: same compile
  output bytes, same daemon IC store, no warning attributable to a flag
  that semantically does nothing on JVM.
- The change must be the smallest one that satisfies the seven feature
  requirements; no new abstraction, no new wire-protocol field, no new
  `kolt.toml` schema.
- Pre-v1 policy: cut breaking changes cleanly without migration shims.

## Decision Outcome

Chosen: **introduce a closed `Profile` enum, parse `--release` once at
the CLI boundary, and thread the typed token through every path producer
and command builder that currently consumes `BUILD_DIR` or
`NATIVE_IC_CACHE_DIR`**. Native compile arg routing branches on the
enum; JVM consumers accept it for symmetry but ignore it for arg
construction. Alternatives are listed at the end.

### §1 CLI flag and profile token

`kolt.cli.Main.parseKoltArgs` extracts `--release` alongside the existing
`--no-daemon` and `--watch` kolt-level flags, returning a typed
`KoltArgs` with a non-null `Profile`. The token is computed once and
threaded into `doBuild`, `doCheck`, `doTest`, `doRun`, `watchCommandLoop`,
and `watchRunLoop`. Watch mode captures the initial-invocation profile;
profile cannot change mid-loop — users cancel and re-invoke to switch.

The flag is positional-agnostic at the kolt level (same as
`--no-daemon` / `--watch`). Anything after `--` is passthrough and is
not interpreted as a profile flag.

`kolt.toml` does not gain `[profile.dev]` / `[profile.release]` sections.
The active profile is determined solely from the command line; this
keeps the schema small and avoids precedence rules between flags and
config that the feature does not need.

### §2 Native compile routing and path partition

`Profile.Debug.dirName == "debug"` and `Profile.Release.dirName ==
"release"` are the single source of truth for profile literals.

Native link-stage command (`Builder.nativeLinkCommand`,
`Builder.nativeTestLinkCommand`):

- Adds `-opt` if and only if `profile == Profile.Release`.
- Adds `-g` if and only if `profile == Profile.Debug`.
- Klib library stage (`nativeLibraryCommand`,
  `nativeTestLibraryCommand`) stays profile-agnostic — both flags
  control properties of the final binary, not of the intermediate klib.

Path partition for kolt-managed artifacts:

- `build/<profile>/<name>.kexe` (Native app).
- `build/<profile>/<name>-test.kexe` (Native test).
- `build/<profile>/<name>-klib` and `<name>-test-klib` (Native klibs).
- `build/<profile>/<name>.jar` (JVM jar; see §3).
- `build/<profile>/<name>-runtime.classpath` (JVM kind=app manifest;
  ADR 0027 §1).
- `build/<profile>/.ic-cache` (project-local Native IC cache).

`build/classes/` and `build/test-classes/` (kotlinc compile output)
remain flat. They are intermediate JVM directories and partitioning
them would not satisfy any requirement.

### §3 JVM no-op contract

Acceptance Criteria 3.1–3.3 are met as follows:

- **3.1 Same compile bytes:** `Builder.checkCommand` and
  `TestBuilder.testBuildCommand` accept `profile` for symmetry but do
  not use it for arg construction (the parameter is annotated
  `@Suppress("UNUSED_PARAMETER")`). Per-profile compile invocations
  produce byte-identical kotlinc argv lists.
- **3.2 No warning:** the parser silently consumes `--release`; no
  branch in any JVM code path emits a deprecation, warning, or error
  attributable to the flag.
- **3.3 Same JVM IC path:** `KoltPaths.daemonIcDir` is unchanged; the
  JVM daemon's `IcStateLayout.workingDirFor` continues to compute
  `<icRoot>/<kotlinVersion>/<sha256(projectRoot).take(16)>` regardless
  of profile. The wire protocol for the JVM daemon (`Compile`) gains
  no `Profile` field.

The JVM jar still partitions under `build/<profile>/<name>.jar`
(Requirement 4). This is observable behaviour but does not violate the
no-op contract: the file's content is byte-identical between profiles
modulo timestamp; the *path* differs because Requirement 4 is target-
agnostic. Users that need a single canonical artifact location should
pick a profile and stick with it; switching profiles is an explicit
opt-in and the path partition is the documented invariant.

The reasons `--release` is a no-op on JVM, captured here for posterity
so the next reviewer does not relitigate them:

- kotlinc bytecode shows no significant difference under common
  optimization toggles for kolt's workload.
- `-Xno-call-assertions` is not the default and is not silently safe to
  enable across all projects.
- Line numbers are emitted by kotlinc unconditionally; there is no
  "strip line numbers under release" knob to attach.
- JVM reproducibility (epoch-zero timestamps, IC bypass) is a separate
  concern with its own design surface and is deferred.

### §4 Distribution script

`scripts/assemble-dist.sh` invokes `"$KOLT" build --release` for the
root build and for both daemon sub-builds (`kolt-jvm-compiler-daemon`
and `kolt-native-compiler-daemon`). The two daemon sub-builds are
JVM-target apps where `--release` is a no-op for compile arg shape,
but the script passes the flag uniformly so that:

- The intent ("this is the release pipeline") is explicit at every
  invocation.
- The on-disk artifact paths the script reads from
  (`<daemon>/build/release/<daemon>.jar` and the matching runtime
  classpath manifest) align with the root binary path
  (`build/release/kolt.kexe`).
- Future-proofing: if a daemon sub-build ever gains a Native target,
  the `--release` flag is already in place and would route `-opt` /
  `-g` correctly without touching the script.

`DriftGuardsTest.profileLiteralsAndDistScriptAreInSync` pins both
`Profile.dirName` literals and the `kolt build --release` invocation in
the script; renaming either end without updating the other fails the
test.

### §5 Issue #261 schema misattribution

Issue #261's Definition of Done lists "daemon IC store split into
`~/.kolt/daemon/ic/<version>/<projectId>/<profile>/`". The directory
`~/.kolt/daemon/ic/` is owned exclusively by the JVM compiler daemon
(ADR 0019 §5; `IcStateLayout.workingDirFor`); JVM is declared no-op
under this profile feature, so partitioning that path by profile would
either contradict Requirement 3.3 or apply only to a hypothetical
future Native daemon IC store.

The shipped schema:

- **JVM daemon IC**: `~/.kolt/daemon/ic/<kotlinVersion>/<projectIdHash>/`
  — unchanged. JVM no-op (Requirement 3.3).
- **Native local IC**: `build/<profile>/.ic-cache` — new partition.
  Project-local, not daemon-managed.

The Native daemon (`kolt-native-compiler-daemon`) does not own a
project-local IC store today; the konanc IC cache is the project-local
`build/<profile>/.ic-cache` directory passed via
`-Xic-cache-dir=...` in the link-stage args. There is therefore no
"daemon IC store" on the Native side to partition.

### §6 No migration shim

Pre-v1 policy applies. The release note for the version that ships this
ADR states `rm -rf build/` is the cleanup path for stale `build/.ic-cache`
content from earlier kolt versions. `~/.kolt/daemon/ic/` is unchanged,
so no daemon-side cleanup is needed.

If a downstream tool started writing into `build/.ic-cache` directly
(no kolt does), it would not coordinate with the new partition; this is
acceptable under the same pre-v1 policy and the lock contract from
ADR 0029 §6 applies the same way it did before.

### Consequences

**Positive**

- Daily Native iteration skips `-opt` link time by default.
- Distribution binaries are unambiguously release-built; the
  `curl | sh` installer (#230) can ship optimized artifacts without an
  out-of-band convention.
- JVM observable behaviour is unchanged; users who never touch
  `--release` see no diff.
- Profile literals (`debug` / `release`) are anchored in `Profile.kt`;
  drift against `assemble-dist.sh` is caught by `DriftGuardsTest`.

**Negative**

- A switch between profiles writes a fresh `build/<profile>/` tree;
  users that flip profiles often will notice double the on-disk
  footprint. Pre-v1 policy: documented, not migrated.
- `~/.kolt/daemon/ic/` JVM IC state from before this ADR is
  unchanged but the project-local `build/.ic-cache` from before this
  ADR is orphaned. Documented in the release note.
- No `kolt.toml [profile.*]` configuration; advanced users who want
  per-profile compiler arg overrides have to wait for `extra_compiler_args`
  (separate issue).

### Confirmation

- `Profile` enum is defined in
  `src/nativeMain/kotlin/kolt/build/Profile.kt`. Unit tests in
  `ProfileTest.kt` pin `dirName` mappings.
- `BuilderTest`, `RunnerTest`, `TestBuilderTest`, and
  `RuntimeClasspathManifestTest` pin profile-aware path strings and the
  `-opt` / `-g` routing.
- `DriftGuardsTest.profileLiteralsAndDistScriptAreInSync` pins the
  cross-file invariant for `assemble-dist.sh`.
- `BuildProfileIT` (env-gated `KOLT_PROFILE_IT=1`) drives end-to-end
  alternation and the JVM no-op byte-equality check; the path-layer
  cases run unconditionally.
- `IcStateCleanupTest.WipeNativeIcCacheTest` pins per-profile wipe
  isolation (`Profile.Debug` wipe leaves `Profile.Release` untouched
  and vice versa).
- `Profile` is declared with default (public) visibility rather than
  the `internal` modifier the design draft suggested. The reason is
  mechanical: existing public top-level functions (`runCommand`,
  `nativeRunCommand`, `nativeTestRunCommand`, `testBuildCommand`)
  take `Profile` as a parameter, and Kotlin rejects an internal type
  on a public signature. There is no external module consuming
  `kolt.build.Profile` today (the JVM and native compiler daemons are
  separate Gradle builds with their own classpaths), so the public
  visibility carries no actual API surface beyond the linuxX64
  binary.

## Alternatives considered

1. **`BuildPaths` value class consolidating all per-profile paths.**
   Rejected. The layout is small (six members) and pre-v1 prefers
   reversible changes; adding a new abstraction before macOS / linuxArm64
   forks the layout (#82 / #83) would commit to a shape we have not
   measured. The current approach (Profile threaded through existing path
   functions) is a strict subset of what `BuildPaths` would do.
2. **`Profile` rides on the daemon wire protocol (`Compile` /
   `NativeCompile` messages).** Rejected. Native already embeds the IC
   path in konanc args (`-Xic-cache-dir=...`); JVM is no-op so the wire
   has nothing to carry. Adding a field to a wire protocol that no
   recipient uses is a future-cost without a present benefit.
3. **`kolt.toml [profile.dev]` / `[profile.release]` configuration.**
   Rejected. Cargo's UX is "the flag is the source of truth"; profiles
   are CLI-only by design. A future per-profile compiler-arg override
   feature would land in its own ADR with explicit precedence rules.
4. **Hidden default of `-opt` even without `--release` (matches
   issue #261's mistaken Problem section claim).** Rejected. Survey of
   `Builder.nativeLinkCommand` confirmed konanc was being invoked
   without `-opt`; there was no hidden default to preserve. The chosen
   policy makes the optimization opt-in explicit.
5. **`build/.ic-cache/<profile>/` instead of
   `build/<profile>/.ic-cache`.** Rejected. The latter aligns with the
   artifact path partition: `rm -rf build/release/` cleans both
   release artifact and release IC together. The former mixes concerns
   under a shared root.

## Related

- #261 — tracking issue.
- ADR 0001 — `Result<V, E>` discipline; profile parsing is total so
  this ADR introduces no new error envelope.
- ADR 0014 — Native two-stage compile. The library stage stays
  profile-agnostic (klibs are intermediate); only the link stage
  routes `-opt` and `-g`.
- ADR 0019 §5 — JVM daemon IC store (`IcStateLayout.workingDirFor`).
  This ADR explicitly does not change that path.
- ADR 0023 §1 — kind schema. Library kind stops at stage 1 regardless
  of profile; the artifact path still partitions per profile.
- ADR 0027 §1 — runtime classpath manifest. The manifest now lives at
  `build/<profile>/<name>-runtime.classpath`.
- README.md / README.ja.md / `.kiro/steering/tech.md` /
  `.claude/skills/kolt-usage/SKILL.md` — user-visible surface updated
  in lockstep with this ADR.
