# BTA-API binary-compat spike — verdict

**Issue:** #138 (initial); #143 (follow-up audit + plugin-passthrough spike)
**Adapter compile-time API:** `kotlin-build-tools-api:2.3.20`
**Topology under test:** `URLClassLoader(impl jars, parent = SharedApiClassesClassLoader())` (matches `BtaIncrementalCompiler.create` in `kolt-compiler-daemon/ic`).
**Dates:** 2026-04-17 (initial), 2026-04-18 (#143 audit + plugin spike)

## Verdict: RED across the 2.x line, GREEN within 2.3.x

| impl version | verdict | compiler version | cold | inc   | notes |
|--------------|---------|------------------|------|-------|-------|
| 2.1.0        | **RED** | —                | —    | —     | fails at `KotlinToolchains.loadImplementation` |
| 2.2.20       | **RED** | —                | —    | —     | same failure mode as 2.1.0 |
| 2.3.0        | GREEN   | 2.3.0            | 3730 | 508   | |
| 2.3.10       | GREEN   | 2.3.10           | 3106 | 499   | |
| 2.3.20       | GREEN   | 2.3.20           | 3275 | 423   | |

Wall times in ms. `linear-10` fixture, touch `F2.kt`.

## Failure mode (2.1.0 / 2.2.20)

```
org.jetbrains.kotlin.buildtools.api.NoImplementationFoundException:
    The classpath contains no implementation for org.jetbrains.kotlin.buildtools.api.KotlinToolchains
    at KotlinToolchains$Companion.loadImplementation(KotlinToolchains.kt:171)
Caused by: java.lang.ClassNotFoundException:
    org.jetbrains.kotlin.buildtools.internal.compat.KotlinToolchainsV1Adapter
    at URLClassLoader.findClass(URLClassLoader.java:445)
    ...
    at KotlinToolchains$Companion.loadImplementation(KotlinToolchains.kt:167)
```

Reading the 2.3.20 `KotlinToolchains.loadImplementation` path:

1. `KotlinToolchains` is the 2.3.x entry type. Pre-2.3 impls do not ship a
   `ServiceLoader` service descriptor for it — they ship the old
   `CompilationService` SPI instead.
2. 2.3.x API tries to bridge old impls via a shim class
   `org.jetbrains.kotlin.buildtools.internal.compat.KotlinToolchainsV1Adapter`.
   It `classLoader.loadClass(...)`es that shim on the classloader it was given.
3. The shim lives in the `...internal.compat.*` package. The daemon's
   `SharedApiClassesClassLoader` only exposes `org.jetbrains.kotlin.buildtools.api.*`
   and does *not* share `internal.compat.*`. The child `URLClassLoader` has
   the impl jars but not the shim, so the `loadClass` fails and the whole
   call turns into `NoImplementationFoundException`.

In short: `@ExperimentalBuildToolsApi` made no binary-compat promise, and the
concrete breaking change is a new entry type (`KotlinToolchains` in 2.3.0)
with a compat shim that our classloader topology does not expose.

## Patch-level stability within 2.3.x

2.3.0 / 2.3.10 / 2.3.20 all GREEN. The impl jar for 2.3.0 and 2.3.10 differ
by 1 byte; 2.3.20 is larger but API-compatible. Treat 2.3.x as one
binary-compat family for daemon-spawn purposes.

## Implications for #138

- **The "per-kotlinVersion daemon spawn" sketch as written does not work.**
  The sketch assumes the adapter stays compiled against 2.3.20 and we
  fetch an older `kotlin-build-tools-impl` to match `config.kotlin`. The
  spike shows that combination crashes at `loadImplementation` for any
  impl from before the 2.3.x line.
- **Proposed re-scope: floor = 2.3.0, forward on a per-release basis.**
  Kotlin has no LTS; 2.3.x is the current language release line, 2.4 is
  still EXPERIMENTAL at spike time (2026-04-17). Picking 2.3.0 as the
  floor means the adapter stays a single JAR — no per-API-major
  distribution, no V1 shim investigation — and every daemon user is on
  the tested topology. Below 2.3.0 is subprocess-only, same shape as the
  #136 stop-gap.
- **Forward policy is event-driven, not N-pinned.** Re-run this spike
  when the next language release (2.4.0) hits stable. If API+impl stays
  binary-compat with the 2.3.20-compiled adapter, extend the daemon
  support table and keep the existing JAR. If it drifts, choose then:
  ship a second adapter JAR or let 2.4 fall through to subprocess. We
  deliberately do **not** commit to `N=2` or "latest + previous" up
  front — Kotlin's own cadence (language release every ~6 months,
  tooling releases in between) makes a specific N brittle, and the
  spike harness here (`spike/bta-compat-138`) is the cheap reusable
  input to that judgement call.
- **BtaImplFetcher still lands.** Even with a single supported API line,
  the daemon needs to fetch `kotlin-build-tools-impl:<config.kotlin>`
  for each 2.3.x patch the user pins — the spike confirms 2.3.0 / 2.3.10
  / 2.3.20 all work against the 2.3.20-compiled adapter, so per-patch
  impl fetch is the load-bearing work item. Socket path keeps
  `<kotlinVersion>` as the issue sketches; IC state is already
  version-stamped (ADR 0019 §5).
- **Info-gate wording simplifies.** "Outside tested range" now means
  "not a 2.3.x release" rather than "a specific version kolt has not
  seen." The three-signal distinction (quiet / info / warning) still
  holds. `--no-daemon` remains the permanent escape hatch.

## Not in this spike (flagged for follow-up)

- Whether a custom parent classloader that also exposes `internal.compat.*`
  can resolve the V1 shim and rescue 2.2.x / 2.1.x impls with the current
  2.3.20 adapter. Plausible but untested. Not worth pursuing unless the
  floor-2.3 decision is reversed.
- Re-running this spike at Kotlin 2.4.0 stable — the harness here is
  the input to the forward-policy judgement call. Keep the spike
  checked in for that reason rather than deleting after #138 merges.

---

# #143 follow-up: BTA-API call-site audit and plugin-passthrough spike

Context: PR #142's smoke invalidated ADR 0022 §3's "2.3.x is one
binary-compat family" claim for the plugin path — `BtaIncrementalCompiler`
sets the structured `COMPILER_PLUGINS` key which only exists in BTA-API
2.3.20. Issue #143 asks two things:

1. **Audit** every BTA-API call site in `kolt-compiler-daemon/ic/` against the
   v2.3.0 API surface.
2. **Spike** whether CLI-style `-Xplugin=<path>` passthrough can carry plugins
   to pre-2.3.20 impls. GREEN → file an implementation issue. RED → make the
   2.3.20 plugin gate a documented permanent contract.

## Audit verdict: only the `COMPILER_PLUGINS` path is non-portable

v2.3.0 and v2.3.10 `.api` dumps are **byte-identical** (625 lines each); 2.3.20
adds 193 lines, chiefly the builder-style surface (`JvmCompilationOperation.Builder`,
`JvmClasspathSnapshottingOperation.Builder`, `JvmSnapshotBasedIncrementalCompilationConfiguration.Builder`)
and the structured-plugin surface (`COMPILER_PLUGINS`, `CompilerPlugin`,
`CompilerPluginOption`, `CompilerPluginPartialOrder`).

The builder-style surface is *not* a regression risk for pre-2.3.20 impls
because 2.3.20 ships `Kotlin230AndBelowWrapper`
(`compiler/build-tools/kotlin-build-tools-api/.../internal/wrappers/Kotlin230AndBelowWrapper.kt`)
inside the API jar. `KotlinToolchains.loadImplementation` detects impls
whose `getCompilerVersion() < "2.3.20"` and wraps them in this class, which
synthesises `jvmCompilationOperationBuilder` (delegating to the old
`createJvmCompilationOperation`) and `snapshotBasedIcConfigurationBuilder`
(constructing an ad-hoc `JvmSnapshotBasedIncrementalCompilationConfigurationWrapper`).
The wrapper also bridges arguments via `applyArgumentStrings` /
`toArgumentStrings`.

| Call site in `kolt-compiler-daemon/ic/` | v2.3.0 status | Notes |
|---|---|---|
| `KotlinToolchains.loadImplementation(loader)` | portable | wraps <2.3.20 impls with `Kotlin230AndBelowWrapper` |
| `SharedApiClassesClassLoader()` | portable | unchanged |
| `toolchain.jvm` (extension on `KotlinToolchains`) | portable | inline val → `getToolchain<JvmPlatformToolchain>()` |
| `jvm.jvmCompilationOperationBuilder(sources, outputDir)` | portable via wrapper | wrapper delegates to 2.3.0 `createJvmCompilationOperation` |
| `jvm.classpathSnapshottingOperationBuilder(entry)` | portable via wrapper | wrapper delegates to 2.3.0 `createClasspathSnapshottingOperation` |
| `builder.snapshotBasedIcConfigurationBuilder(...)` | portable via wrapper | wrapper synthesises the Builder + Configuration pair |
| `builder[JvmCompilationOperation.INCREMENTAL_COMPILATION] = icConfig` | portable | Option key present in 2.3.0 |
| `builder[BuildOperation.METRICS_COLLECTOR] = ...` | portable | `BuildOperation.METRICS_COLLECTOR` field present in 2.3.0 |
| `builder.compilerArguments[JvmCompilerArguments.CLASSPATH]` | portable | CLASSPATH key present in 2.3.0 |
| `builder.compilerArguments[JvmCompilerArguments.MODULE_NAME]` | portable | MODULE_NAME key present in 2.3.0 |
| `builder.compilerArguments[CommonCompilerArguments.COMPILER_PLUGINS]` | **2.3.20 only** | structured key added in 2.3.20; pre-2.3.20 impls raise `"available only since 2.3.20"` even on empty list assignment |
| `CompilerPlugin(id, classpath, rawArgs, orderingRequirements)` | **2.3.20 only** | class added in 2.3.20 API; constructed inside `PluginTranslator.translate` before the empty-list guard in `BtaIncrementalCompiler`, so construction happens whenever a user enables any `[plugins]` entry |
| `toolchain.createInProcessExecutionPolicy()` | portable | unchanged |
| `toolchain.createBuildSession()` | portable | unchanged |
| `session.executeOperation(op, policy, logger)` | portable | 3-arg signature present in 2.3.0 |
| `session.executeOperation(op)` | portable | 1-arg convenience present in 2.3.0 |
| `snapshot.saveSnapshot(Path)` | portable | default method on `ClasspathEntrySnapshot` in 2.3.0 |
| `KotlinLogger` implementation (isDebugEnabled, error, warn, info, debug, lifecycle) | portable | identical method surface in 2.3.0 |
| `BuildMetricsCollector.collectMetric(name, type, value)` + `ValueType` enum | portable | identical in 2.3.0 |
| `SourcesChanges.ToBeCalculated` | portable | unchanged |

Only two call sites are non-portable, and both are in the plugin path. The
existing `DaemonPreconditionError.PluginsRequireMinKotlinVersion` gate in
`src/nativeMain/kotlin/kolt/build/daemon/DaemonPreconditions.kt:100` is
sufficient to prevent both from firing on 2.3.0 / 2.3.10. ADR 0022 §3's
family-GREEN claim therefore holds for plugin-free projects across the full
2.3.x line; the §3 wording that needs sharpening is not the floor, but the
conditional on `[plugins]`.

## Plugin-passthrough spike: GREEN

Issue #143 phrases the spike as "can `JvmCompilerArguments.FREE_ARGS` carry
plugins for impls < 2.3.20?" — but `FREE_ARGS` is not a member of the 2.3.0
(or 2.3.20) `JvmCompilerArguments` surface. The actual CLI-passthrough
mechanism in 2.3.0 is `CommonToolArguments.applyArgumentStrings(List<String>)`,
which is the method 2.3.20's `Kotlin230AndBelowWrapper` itself uses to bridge
structured args from the new builder surface to the pre-2.3.20 impl.

The plugin-smoke harness (`plugin-smoke` subcommand in `Main.kt`) extends the
existing matrix with a `@Serializable`-annotated fixture and a serialization
compiler-plugin jar resolved per impl version. The test attaches the plugin
with `applyArgumentStrings(listOf("-Xplugin=<jar>"))` and inspects the
generated `.class` tree. The `Foo$$serializer.class` output is the signal:
that class is only synthesised when the serialization compiler plugin
actually runs.

| impl version | verdict | compile result | has `$$serializer` | notes |
|--------------|---------|----------------|--------------------|-------|
| 2.1.0        | RED     | —              | —                  | `NoImplementationFoundException` (same as #138 spike — pre-floor territory) |
| 2.2.20       | RED     | —              | —                  | same as 2.1.0 |
| 2.3.0        | **GREEN** | COMPILATION_SUCCESS | yes         | `Foo.class`, `Foo$Companion.class`, `Foo$$serializer.class` all emitted |
| 2.3.10       | **GREEN** | COMPILATION_SUCCESS | yes         | same as 2.3.0 |
| 2.3.20       | GREEN   | COMPILATION_SUCCESS | yes                | baseline sanity check |

**Observed gotcha (not yet folded into the adapter):** `applyArgumentStrings`
resets every non-mentioned structured argument to the parser default.
Running `applyArgumentStrings(["-Xplugin=..."])` **after** setting
`MODULE_NAME` via the structured key path reproducibly nulls out
`moduleName`, failing the compile with
`'moduleName' is null!` at `IncrementalJvmCompilerRunnerBase.makeServices`.
The fix is trivially to call `applyArgumentStrings` *before* any structured
`set(...)` calls, but any implementation of the passthrough path must
preserve that ordering.

## Implications for ADR 0022 §3 and #138 follow-up work

- **§3 family-GREEN claim stands for plugin-free projects.** No audit finding
  beyond `COMPILER_PLUGINS` / `CompilerPlugin`. The adapter works end-to-end
  against 2.3.0 / 2.3.10 when `[plugins]` is empty.
- **Plugin-using projects on 2.3.0 / 2.3.10 can also be routed through the
  daemon** — the spike proves the plugin runs under the wrapper. The
  implementation path is a separate issue (see below).
- **No ADR amendment required today.** Once the passthrough is implemented,
  §3 can drop the "plugin-using projects need 2.3.20" carve-out. Until then,
  the existing `KOTLIN_VERSION_FOR_PLUGINS` gate remains correct — it
  over-restricts but does not mis-route.

## Follow-up to file

Open a new issue: "Route plugin-using projects on 2.3.0 / 2.3.10 through the
daemon via `-Xplugin=` passthrough."

- Replace the structured `COMPILER_PLUGINS` assignment in
  `BtaIncrementalCompiler` with a codepath that falls back to
  `applyArgumentStrings(listOf("-Xplugin=<jar1>", "-Xplugin=<jar2>", ...))`
  when the configured Kotlin version is < 2.3.20 (or unconditionally, once
  the passthrough path is verified equivalent for 2.3.20 too).
- Call `applyArgumentStrings` before the structured `set(...)` calls so the
  parser-default reset does not wipe `MODULE_NAME` / `CLASSPATH` (observed
  in this spike).
- Drop the `PluginsRequireMinKotlinVersion` precondition once the above
  lands; leave the precondition's error path in place only as a safety net
  until the next release cycle confirms no regressions.
- Plugin options (`-P plugin:...:key=value`) are out of scope for the initial
  implementation — serialization needs no options, and the `PluginTranslator`
  already passes `rawArguments = emptyList()`.

## Raw run log

- `/tmp/bta-compat-work/run.log` — initial #138 compat matrix.
- `/tmp/bta-compat-plugin-work/run.log` — #143 plugin-smoke matrix.

## Reproducing

```
# #138 BTA compat matrix
./gradlew -p spike/bta-compat-138 run --args="bta-compat fixtures/linear-10 /tmp/bta-compat-work"

# #143 plugin passthrough
./gradlew -p spike/bta-compat-138 run --args="plugin-smoke fixtures/plugin-serialization /tmp/bta-compat-plugin-work"
```
