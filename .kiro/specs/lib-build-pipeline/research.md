# Gap Analysis: lib-build-pipeline

Source: requirements.md (R1–R5). Codebase at 2026-04-22 (ccsdd branch).

## TL;DR

- **The work is smaller than the issue description implies.** The JVM jar path
  is already thin (`-include-runtime` is not used anywhere in the codebase).
  The native flow already produces a `.klib` in stage 1. The real code delta
  is: parser (~lift rejection + conditional `main`), native builder (skip
  stage 2 when `kind = "lib"`), CLI guard (`kolt run` reject before work).
- **One hard coupling point**: `Builder.kt:112` hardcodes `-e config.build.main`
  in `nativeLinkCommand`. All other non-build commands are already decoupled
  from `main`.
- **Option A (extend)** is the clear fit. The three seams are already
  separated (`kolt.config`, `kolt.build`, `kolt.cli`) per structure.md.
- **Effort**: S–M (3–5 days). **Risk**: Low.

## Requirement-to-Asset Map

| Req | Subsystem | Current asset | Gap / change |
|-----|-----------|---------------|--------------|
| R1 parser | `kolt.config.Config` | `validateKind` (`Config.kt:128-140`) rejects lib. `RawBuildSection.main` (`Config.kt:106`) is non-nullable. `validateMainFqn` (`Config.kt:186`). Default kind = "app". | **Missing**: lib acceptance; nullable `main`; conditional main rule with exact ADR 0023 §1 text. |
| R1 tests | `ConfigTest.kt` | `kindLibIsRejectedAsNotYetImplemented` (`ConfigTest.kt:311-336`) pins current reject behavior. `MainTest.kt` covers FQN validation. | **Replace** `kindLibIsRejectedAsNotYetImplemented`; **add** lib+no-main OK / lib+main error / app+no-main error / app+main OK matrix. |
| R2 JVM build | `kolt.build.Builder` | `jarCommand` (`Builder.kt:221-227`) = `jar cf <output> -C <classes> .` — already thin, no `-include-runtime` anywhere (grep: 0 matches). | **Mostly already satisfied**. Add assertion test that jar for lib has no `Main-Class` manifest. Confirm kotlinc JVM compile path doesn't emit Main-Class either (verify; current JVM build is already "classes only"). |
| R3 Native build | `kolt.build.Builder` | `nativeLibraryCommand` (`Builder.kt:68-94`) = stage 1 klib. `nativeLinkCommand` (`Builder.kt:96-124`) = stage 2, uses `-e config.build.main` (`Builder.kt:112`). | **Missing**: kind-branch in `doNativeBuild` (skip stage 2 when lib). `-e main` reference at `Builder.kt:112` requires `main` be present — effectively keeps stage 2 app-only. |
| R3 tests | `BuildCommandsTest.kt:225-327`, `NativeDaemonIntegrationTest.kt:46-100` | Existing tests pin `-e com.example.main` in link output (app path). | **Keep** app-path tests. **Add** lib-path test asserting stage 2 is not invoked / no `.kexe` materializes. |
| R4 run guard | `kolt.cli.BuildCommands.doRun` (`BuildCommands.kt:406-435`) | No kind check; assumes executable exists. | **Missing**: top-of-function kind guard, emit `library projects cannot be run`, return non-zero before `outputKexePath` / `jvmMainClass` resolution. Applies to both native and JVM paths. Watch-run (`WatchLoop.kt:261-330`) delegates to `doBuild`+`doRun` so the same guard covers `kolt run --watch`. |
| R5 test | `kolt.cli.BuildCommands.doNativeTest` (`BuildCommands.kt:492-572`), `doTest` (`BuildCommands.kt:437`), `TestBuilder.kt:12-36` | **Already decoupled from `main`.** Native test uses `-generate-test-runner` (not `-e main`). JVM test uses JUnit Platform. | **No code change expected**. Requirement is a non-regression assertion — add a test confirming `kolt test` works against a lib config. |
| Adjacent: LSP | `kolt.build.Workspace.generateWorkspaceJson` (`Workspace.kt:13-106`) | Never references `config.main`. | No change. `--check`/IDE workspace for libs works out of the box. |
| Adjacent: fmt/check/deps | `Formatter.kt`, `doCheck` (`BuildCommands.kt:60-83`), `DependencyCommands.kt:205-293` | None reference `config.main`. | No change. |
| Adjacent: `kolt init` | `kolt.config.Init.generateKoltToml` (`Init.kt:9-21`) | Unconditionally emits `main = "main"`. | **No change** — app default is still correct. Lib scaffolding is #28, out of scope. |
| Adjacent: watch | `WatchLoop.watchCommandLoop` / `watchRunLoop` | Delegates to `doBuild` / `doRun`. | Inherits kind-branching and run-guard for free; no direct change. |

## Gaps, Unknowns, Constraints

- **Missing**: parser lib-path, parser conditional-`main`, native builder kind-branch at stage 2, CLI lib-run guard. All four are surgical edits inside existing functions.
- **Unknowns** (Research Needed for design phase):
  1. **Exact error-string formatting**: ADR 0023 §1 prose uses backticks around `main` (`` `main` has no meaning for a library; remove it ``). The actual runtime error constant may or may not keep backticks. Requirements use substring match (`main has no meaning for a library; remove it`) which is format-agnostic — design should pick one concrete string and lock it in.
  2. **Where the kind branch lives for native**: at the `Builder`-level (conditional composition inside `doNativeBuild`) vs. at the `CompilerBackend` selection layer. The cheapest fix is `doNativeBuild`-level: call `nativeLibraryCommand` then gate `nativeLinkCommand` on kind. `CompilerBackend` already abstracts over daemon/subprocess; it does not decompose library-vs-link. Leaving the gate at the Builder level preserves the existing seam.
  3. **Whether `kolt run --watch` should prompt reject per tick or once**: current `watchRunLoop` calls `doBuild` then `doRun`. If `doRun` rejects with the lib error, the watch loop will emit the error on every change tick. Design should decide: detect kind at watch entry and reject immediately (cleaner UX) vs. let the per-tick rejection surface naturally (less code). Recommend the former.
- **Constraints**:
  - ADR 0014 native two-stage flow stays untouched; this spec only chooses when to stop (aligns with steering/structure.md "this spec only chooses when to stop").
  - ADR 0023 §1 error text is authoritative — verify against `docs/adr/0023-target-and-kind-schema.md` when writing the constant.
  - Pre-v1: no migration shim. Making `RawBuildSection.main` nullable is a breaking change only for third-party tooling that deserializes `RawBuildSection` directly, which does not exist outside this repo.
  - No cross-validation of `kind × target` needed — lib works on all supported targets (jvm, linuxX64) without additional guards.

## Implementation Approach Options

### Option A: Extend existing components (RECOMMENDED)

**Fit**: the three responsibility seams (`kolt.config`, `kolt.build`, `kolt.cli`)
are already separated. Each change is a small, local edit inside an
existing function.

- `Config.kt`: make `RawBuildSection.main` nullable, lift the lib reject in
  `validateKind`, add conditional-`main` rule in `parseConfig` using ADR
  0023 §1 canonical text.
- `Builder.kt`: add `if (config.kind != "lib")` gate around the stage 2 call
  in `doNativeBuild`; JVM path stays as is (already thin, no Main-Class).
- `BuildCommands.kt`: prepend a kind check in `doRun`; also prepend in
  `watchRunLoop` to avoid per-tick error spam.
- `ConfigTest.kt`: replace the `kindLibIsRejectedAsNotYetImplemented`
  fixture with the new matrix (lib+no-main OK / lib+main error / app+no-main
  error / app+main OK / kind default).
- Add end-to-end fixtures: a lib JVM project produces thin jar without
  Main-Class; a lib native project produces `.klib` but no `.kexe`;
  `kolt run` on a lib rejects.

**Trade-offs**:
- ✅ No new files, no new abstractions.
- ✅ Each seam stays below existing size; no bloat.
- ✅ Preserves ADR 0014 / ADR 0013 stage layering.
- ❌ Parser `parseConfig` grows by one validation rule — still small.

### Option B: Create new components

Introduce e.g. `LibraryBuilder` / `ApplicationBuilder` sealed variants of a
new `KindBuilder` interface, dispatching from the top of `doBuild`.

- ✅ Cleaner long-term if a third kind (`kmp-lib`, `plugin`) gets added.
- ❌ Overkill for two variants with 90% shared code. The library path
  literally just omits stage 2 — splitting into siblings duplicates the
  shared stage 1 invocation.
- ❌ Deviates from Option A's "minimum code delta" principle without
  earning a concrete future use case (no third kind is planned).

### Option C: Hybrid

Extend `Config` and `doRun` (Option A), but introduce a minimal
`NativeBuildPlan` record that names `{ stage1, stage2? }` in `Builder`.

- ✅ Makes the "stop after stage 1" decision explicit and testable in
  isolation.
- ✅ Leaves room for future variants (e.g., static-binary toggle).
- ❌ Nontrivial refactor to existing `Builder` code surface; higher risk of
  touching ADR 0014 machinery.
- ❌ Current call site is already read as "call stage1 then stage2"; the
  proposed record is documentation, not abstraction gain.

**Recommendation**: Option A. Revisit Option C if a second "stop early"
case appears post-v1 (e.g. `kolt check` wanting stage 1 artifacts).

## Effort & Risk

- **Effort: S–M (3–5 days)**. Code changes total ~80 LOC across 3 files;
  the bulk is test fixtures and the `ConfigTest` matrix. One extra day for
  integration dogfooding (build the `kolt-compiler-daemon/` or a throwaway
  lib to confirm `.klib`-only output).
- **Risk: Low**. Existing patterns, well-bounded issue, ADR-guided, pre-v1
  so no migration concerns. The only reversible concern is locking in the
  runtime error text — trivially changeable later if design chooses wrong.

## Recommendations for Design Phase

- **Prefer Option A**.
- **Key design decisions to commit**:
  1. Exact error constants (parser + run-reject), living as private `const val`
     next to their emitter. Cite ADR 0023 §1 in the comment.
  2. Kind-branch placement: inside `doNativeBuild` at the `Builder` layer
     (not at `CompilerBackend`), matching the discovery recon path.
  3. `kolt run --watch` behaviour: reject at watch entry, not per-tick.
  4. Nullable shape of `RawBuildSection.main`: `String?` with a single
     conditional-validation gate in `parseConfig`.
- **Research items to carry forward**: none external. All open points are
  local to the design choices listed above.
- **Test fixtures to build**:
  - Unit: `ConfigTest` matrix replacement.
  - Native: a `testfixture` minimal lib kolt.toml + assertion that the
    produced artifact tree has `.klib` only.
  - JVM: a minimal lib kolt.toml + assertion that the jar manifest has no
    `Main-Class` and contains no non-project classes.
  - CLI: `BuildCommandsTest` coverage for `doRun` lib reject.

---

## Synthesis Outcomes (Design Phase)

Applied generalization / build-vs-adopt / simplification lenses before
writing `design.md`.

### Generalization
- R1 (parser) / R3 (native build) / R4 (run reject) all branch on the same
  signal: `KoltConfig.kind == "lib"`. Rather than sprinkling the string
  literal at each call site, introduce a single `fun KoltConfig.isLibrary():
  Boolean` (file-local in `Config.kt`) and use it everywhere. This is
  interface-level generalization with no implementation cost.
- R5 (`kolt test`) is a pure non-regression: the test flow already does
  not read `config.build.main`. No design-level generalization buys
  anything there; it is satisfied by existing decoupling.

### Build vs. Adopt
- Nothing external to adopt. ADR 0014 two-stage flow already exists.
  ktoml supports `String?` natively. `jar cf` already produces the thin
  jar shape R2 requires. There is no library or protocol to bring in.

### Simplification
- **Reject**: adding a sealed `BuildOutput` sum type to discriminate
  `NativeLibrary` / `NativeExecutable` / `JvmJar` at the `doBuild` return
  boundary. The existing `BuildResult(config, classpath, javaPath)` is
  sufficient — success is success; artifact kind is derivable from
  `config.kind` + `config.build.target` at the call sites that format
  output messages.
- **Reject**: introducing a new `ConfigError.LibraryWithMain` variant.
  Reuse the existing `ConfigError.ParseFailed(message)` — the canonical
  ADR 0023 §1 string is the `message`. The `when (error) { is ParseFailed -> ... }`
  in `loadProjectConfig` stays exhaustive without a refactor.
- **Reject**: introducing a new exit code constant for the lib-run
  rejection. Reuse `EXIT_CONFIG_ERROR` — the invocation itself is a
  misuse of a valid config, which matches the existing error category.
- **Reject**: introducing a new top-level `RunError` ADT for `doRun`.
  The project's CLI pattern emits errors via `eprintln(...)` + `return
  Err(EXIT_*)` where `EXIT_*: Int`. Follow that pattern.
- **Accept**: the three-line change surface (parser / native builder /
  run guard) + `isLibrary()` helper is the minimum that satisfies all
  requirements. No new types, no new files for production code; only
  new test files.

### Code Grounding Confirmed
- `KoltConfig` (not `Config`) is the public data class; `kind: String =
  "app"` lives at the top level (line 82).
- `BuildSection.main: String` (line 62) and `RawBuildSection.main: String`
  (line 106) both become `String?`.
- `validateKind` (line 128) is the single place that gates `"lib"`.
- `doNativeBuild` (`BuildCommands.kt:228`) returns
  `Result<BuildResult, Int>`. Kind gate fits inside before the link step.
- `BuildResult` (`BuildCommands.kt:28`) is unchanged by this spec.
