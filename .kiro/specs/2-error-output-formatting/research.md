# Gap Analysis — 2-error-output-formatting

## 1. Current State Investigation

### Output sink (single extension point)
- `kolt/infra/FileSystem.kt:433` — `eprintln(msg: String)` writes to `STDERR_FILENO` directly via posix `write()`. **No buffering, no formatter layer.** This is the single chokepoint for stderr writes; `println` is used for stdout.

### Existing partial renderer patterns (two of them already exist)
- `kolt/usertool/ToolError.kt` — sealed `ToolError` with `abstract fun formatStderr(): String`. Per-variant rendering, multi-line via plain string concat. Comment in source even says: *"`formatStderr()` emits the variant-specific prefix that R5.4 ('cause-distinguishable surface') relies on"* — already in this style.
- `kolt/resolve/Resolver.kt:65` — top-level `formatResolveError(error: ResolveError): String`, uses `buildString` + `appendLine` for multi-line context (e.g., `error: sha256 mismatch ... \n  expected: ... \n  got: ...`). Indentation is `"  "` ad hoc.

→ **A pattern is emerging but is duplicated in string-builder form**: each renderer hardcodes its own `"error: "` prefix and its own indentation rule. The spec needs to centralize prefix + color + indent rule, not invent rendering from scratch.

### Mass call sites with raw string `error:` / `warning:`
- `kolt/cli/BuildCommands.kt` — 20+ inline `eprintln("error: ...")` and `eprintln("warning: ...")` sites (build, check, clean, watch).
- `kolt/cli/DependencyCommands.kt` — similar pattern, 8+ sites.
- `kolt/cli/Main.kt:102` — `eprintln("error: unknown command '${filteredArgs[0]}'")`. Extension point for Did-you-mean.
- `kolt/cli/Main.kt:159+` — `printUsage()` writes via `eprintln(...)` for the help block.

→ All these need to migrate to a typed helper that owns the `error:` prefix, the color decision, and the context-line indent.

### TTY detection — already wired
- `kolt/cli/ScaffoldIO.kt` imports and uses `platform.posix.isatty`. **No new cinterop needed.** Color policy can call `isatty(STDERR_FILENO)` / `isatty(STDOUT_FILENO)` immediately.

### CLI flag plumbing — single extension point
- `kolt/cli/Main.kt:127-141` — `parseKoltArgs(argList)` extracts kolt-level flags (`--no-daemon`, `--watch`, `--release`, `-D<k>=<v>`) into `KoltArgs`. **Adding `--no-color` is a 3-line change here** (constant + filter + field).

### kolt.toml line numbers — recoverable but indirect
- `kolt/config/Config.kt:520-524` catches `SerializationException` / `IllegalArgumentException` and renders `"failed to parse kolt.toml: ${e.message}"`.
- ktoml-core 0.7.1 (`com/akuleshov7/ktoml/exceptions/TomlDecodingException.kt`) defines:
  - `internal open class ParseException(message, lineNo) : TomlDecodingException("Line $lineNo: $message")`
  - `internal class IllegalTypeException(message, lineNo) : TomlDecodingException("Line $lineNo: $message")`
  - `internal class NullValueException(propertyName, lineNo) : ...("Line: <$lineNo>")` (different format)
  - `internal class InvalidEnumValueException(value, descriptor, lineNo) : ...("Line $lineNo: ...")`
  - **NO line number** on `MissingRequiredPropertyException`, `UnknownNameException`, `InternalDecodingException`.
- The `internal` modifier means kolt cannot pattern-match the exception subclass from outside ktoml — only `e.message` text is stable.
- ktoml's own `InvalidEnumValueException` already produces `"Did you mean <X>?"` for enum typos via `closestEnumName` (internal utility). Inspiration only — not reusable.

### Existing fuzzy match — none
- No Levenshtein, edit distance, or "Did you mean" anywhere in kolt source. ktoml's `closestEnumName` is `internal` so unusable from kolt. **New utility required** (~30 lines for normalized Levenshtein).

### Subprocess output forwarding (kotlinc / konanc / daemon)
- `kolt/build/CompilerBackend.kt` — `CompileError.CompilationFailed(exitCode, stdout, stderr)` carries forwarded subprocess output as separate fields.
- `kolt/build/SubprocessCompilerBackend.kt:25` — `CompileOutcome(stdout = "", stderr = "")` — subprocess inherits parent stderr fd directly (no capture). Comment: *"konanc inherits the parent process's stderr fd"*.
- `kolt/build/nativedaemon/NativeDaemonBackend.kt:269` — daemon path captures stderr to a string and surfaces it through `NativeCompileOutcome`.
- BuildCommands.kt:526 — on failure, `eprintln(body)` then `eprintln(formatCompileError(...))`. So **the kolt headline is one line, then the multi-line forwarded output, then no trailing prefix**. Already roughly the shape R6.4 wants.

### Existing exit codes
- `kolt/cli/ExitCodes.kt` carries the constants `EXIT_CONFIG_ERROR=2`, `EXIT_DEPENDENCY_ERROR=3`, `EXIT_TOOL_ERROR=7`, `EXIT_COMMAND_NOT_FOUND`, etc. Test pin (`ToolErrorTest.exactlyThreeVariantsRouteThroughExitToolError`) already exists. **No changes needed** — R1.6 / R7.2 are pure preservation.

## 2. Requirement-to-Asset Map

| Req | Capability needed | Current asset | Gap |
|----|-------------------|---------------|-----|
| R1 (severity prefix + indent) | Single helper that owns `error:` / `warning:` / `note:` prefix + indented context lines | `eprintln`, two ad hoc renderers (`ToolError.formatStderr`, `formatResolveError`) | **Constraint**: helper must subsume both ad hoc renderers without losing exit-code typing. **Missing**: typed `eprintError(headline, context, hint)` API. |
| R1.5 (stdout discipline) | Distinguish severity (stderr) from user-requested output (stdout) | Already correct — `println` for stdout, `eprintln` for stderr | None. Preservation only. |
| R1.6 / R7.2 (exit code preservation) | No code path changes | Exit-code constants pinned by tests | None. |
| R2 (TTY + NO_COLOR + --no-color) | Color policy module that consults TTY + env + flag, per-stream | `isatty` already imported; `parseKoltArgs` extensible | **Missing**: `ColorPolicy` decision module (new). `--no-color` flag entry in `parseKoltArgs`. |
| R3.1 (file path) | Always include `kolt.toml` absolute path | `Config.kt:521` swallows path | **Missing**: pass path through `ConfigError.ParseFailed` so renderer can include it. |
| R3.2 (line / column) | Surface ktoml's `Line N:` from exception message | ktoml encodes lineNo in `e.message` text only (`internal` types not pattern-matchable) | **Research Needed**: regex extraction of `^Line (\d+): ` from `e.message`; document message-format fragility (ktoml minor versions could reword). |
| R3.3 (key path) | Identify the offending kolt.toml key path | Currently lost in catch | **Missing**: route ktoml `UnknownNameException` / `MissingRequiredPropertyException` content into a dedicated `ConfigError` variant that carries key path. |
| R3.4 (Did-you-mean key) | Closest-match key suggestion | No prior art | **Missing**: Levenshtein utility + per-section known-key list. |
| R4 (resolve context) | Coordinate / parent / repos / hashes | Already mostly present in `ResolveError` data classes + `formatResolveError` | **Constraint**: existing variants already carry coordinate; only need format normalization through new helper. R4.4 (checksum mismatch detail) — `Sha256Mismatch(groupArtifact, expected, actual)` already complete. |
| R5.1 (recovery hint) | Per-error variant `hint: String?` | `LockfileMismatch` already says `"Run \`kolt update\` to refresh tool pins."` inline | **Constraint**: hints currently embedded in headline text; should split into separate `note:` continuation line per R1 indentation rule. |
| R5.2-3 (subcommand / flag Did-you-mean) | Closest-match utility + known commands list | Known commands are hardcoded in `Main.kt` `when`; no fuzzy match | **Missing**: utility + known-name enumeration. |
| R6.1 (no kolt prefix on subprocess) | Forward subprocess stderr verbatim | Already correct (BuildCommands.kt:526 pattern) | None. Preservation. |
| R6.2 / R6.3 (color pass-through / strip) | Color-aware forwarding | Native subprocess inherits parent fd directly (no buffering); daemon path captures into string | **Research Needed**: how to honor `--no-color` for subprocess output. Two viable approaches — (a) env-propagate `NO_COLOR=1` to subprocess + daemon, (b) post-strip ANSI regex. (a) is cleaner but requires daemon protocol awareness. |
| R7.1 (no ANSI in JSON) | Color policy aware of structured-output context | `kolt info --format=json` writes JSON to stdout via `println` | **Missing**: ensure stdout color policy is bypassed when `--format=json` is in effect (or simpler: structured output never reaches the color helper in the first place). Likely already correct since stdout `println` doesn't invoke severity helper. |

## 3. Implementation Approach Options

### Option A — Extend existing components only
Centralize prefix + color into a typed `eprintError` / `eprintWarning` / `eprintNote` triplet wrapping `eprintln`. Existing two renderers (`ToolError.formatStderr`, `formatResolveError`) drop their `error:` prefix and return only headline + structured context. Mass-migrate raw `eprintln("error: ...")` call sites.

- ✅ Minimal new files (one helper module + one ColorPolicy)
- ✅ Reuses `eprintln` extension point and `parseKoltArgs` flag plumbing
- ✅ Existing renderer ADTs (`ToolError`, `ResolveError`) keep their shapes
- ❌ ~30+ call sites change mechanically — partial-migration risk
- ❌ `formatResolveError` multi-line `buildString` needs careful split between headline / context
- ❌ R3.2 line-number extraction via regex on ktoml's `e.message` is fragile (acknowledged Research Needed)

### Option B — New `Diagnostic` ADT layer
Define `data class Diagnostic(severity, headline, context: List<String>, hint: String?)` and a renderer module. All error paths produce `Diagnostic` values; CLI dispatcher renders. Existing `ToolError.formatStderr` / `formatResolveError` are replaced by `toDiagnostic()` returning the ADT.

- ✅ Clean ADT-driven flow, easy unit testing of rendering
- ✅ Severity / context / hint carried as data, not strings — easier to test "Did you mean" attachment
- ✅ Future JSON output spec can serialize Diagnostic directly
- ❌ Larger surface change — every error path call site changes, not just the eprintln callers
- ❌ Existing two ad hoc renderers must be rewritten, not lightly tweaked
- ❌ Risk of churning test fixtures pinned on current string formats

### Option C — Hybrid: typed eprintln helpers + Diagnostic for new error contexts
Phase 1 (this spec): introduce `eprintError(headline, context = emptyList(), hint = null)` / `eprintWarning(...)` / `eprintNote(...)` plus `ColorPolicy`. Refactor existing renderers to drop their hardcoded `error:` prefix and call the helper. Wire `--no-color` flag and `NO_COLOR` env. Add Levenshtein utility + Did-you-mean wiring at Main dispatcher and Config decoder.

Phase 2 (out of scope, future): when JSON diagnostic output is added, introduce `Diagnostic` ADT around the same helper.

- ✅ Smaller migration surface — `eprintln("error: X")` → `eprintError("X")` is mechanical and greppable
- ✅ Existing `ToolError.formatStderr` style preserved (returns a tuple `(headline, context)`); only the prefix concatenation moves
- ✅ Tests pin headline format separately from prefix, so refactor is bisectable
- ❌ Two patterns coexist (positional helper call vs future Diagnostic ADT) until Phase 2 — minor cost

## 4. Recommendation for design phase

**Preferred approach: Option C (Hybrid)**. Phase 1 alone covers all 7 requirements without inventing a Diagnostic ADT this spec doesn't strictly need. The existing two renderers (`ToolError`, `ResolveError`) already prove the headline-plus-context shape works; centralizing prefix + color is sufficient.

### Key design decisions for design.md
1. **Color policy module shape**: `enum class ColorMode { Auto, Always, Never }` decided once at startup from `(--no-color flag, NO_COLOR env, isatty)`; cached per-stream (stderr vs stdout) since they have independent TTY state.
2. **Renderer API surface**: `eprintError(headline: String, context: List<String> = emptyList(), hint: String? = null)` etc. Each context line is rendered indented (e.g., `"  ${line}"`) under the colored prefix; hint is a separate `note:`-prefixed continuation.
3. **Where `--no-color` is parsed**: kolt-level flag in `parseKoltArgs`, threaded into the ColorPolicy initializer. Per-command parsing rejected — color decision must be uniform across the run.
4. **kolt.toml line-number extraction**: regex `^Line (\d+): ` against `e.message`, applied in a new `ConfigError.ParseFailed(path, lineNo: Int?, detail: String)` shape. Add a lockstep test pinning ktoml version → message format. ktoml major-version bumps require revisiting.
5. **Subprocess color stripping (R6.3)**: prefer env-propagation (`NO_COLOR=1` set in subprocess env when color is disabled) over post-strip. kotlinc + konanc both honor `NO_COLOR`. Daemon path is more involved — confirm in design that daemon subprocess wrapper passes the env through.
6. **Did-you-mean utility**: new `kolt.cli.Suggestions` module with normalized Levenshtein, threshold ≤ 2 (or `min(2, |target| / 4)` adaptive). Candidate sets: known kolt subcommands (hardcoded list), known global flags (hardcoded list), known kolt.toml top-level keys (derived from `KoltConfigRaw` `@SerialName` annotations or hardcoded list).
7. **`note:` color choice**: cyan or bright-black recommended (visually distinct from yellow/red, plays well on both light and dark backgrounds). Design phase should pin one for cross-platform consistency.

### Research Needed (carry forward)
- **R3.2 ktoml message-format stability**: Verify ktoml ≥ 0.7.1 always emits `^Line N: ` prefix on `ParseException` / `IllegalTypeException` / `InvalidEnumValueException`. Add a regression test against a synthetic broken `kolt.toml` to pin the format. ktoml minor-version bumps run this test.
- **R6.3 daemon env propagation**: Walk the daemon spawn path (`kolt-jvm-compiler-daemon`, `kolt-native-compiler-daemon`) to confirm child process env inherits the parent's `NO_COLOR`. If not, decide between (a) protocol extension to ship the env, (b) post-strip on the captured stderr blob.
- **R5.1 recovery hint catalog**: Enumerate the failure-class → recovery-command pairs that should fire `note:` lines. Initial candidates: stale-lockfile → `kolt update`; missing toolchain → `kolt toolchain install`; missing local jar → `kolt fetch`. Verify with current error variant coverage.
- **R7.1 JSON path verification**: `kolt info --format=json` emits via `println` — confirm there is no path where severity helper writes to stdout in JSON mode.

## 5. Effort & Risk

- **Effort: M (3–7 days)**. Single-developer week. Bulk is mechanical refactor of ~30 raw `eprintln("error: ...")` sites + 2 renderer migrations + 1 ColorPolicy module + 1 Suggestions utility + 1 ktoml message regex extractor. Tests pin both pre- and post-format to catch partial migration.
- **Risk: Low–Medium**.
  - Low for color policy, severity helper, Did-you-mean wiring (extending established patterns).
  - Medium for ktoml line-number extraction (string-format coupling) and subprocess color stripping (daemon protocol awareness).
  - Cross-cutting test surface needs investment: one snapshot test per renderer covering color-on / color-off / NO_COLOR / non-TTY.
