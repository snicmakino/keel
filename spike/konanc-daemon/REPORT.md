# Spike #166: konanc daemon feasibility

## Summary

- K2Native can run as a persistent JVM process. `K2Native.exec()` is safe to call repeatedly in the same JVM — no state leakage observed across 4 consecutive invocations per fixture.
- Stage 1 (source → klib) warm speedup: **1.6–7.8x** depending on fixture size. Small projects benefit most (JVM startup dominates); larger projects see diminishing returns as compilation work itself grows.
- Stage 2 (klib → kexe, linking) warm speedup: **1.5x** on native-10 (11.6s warm vs 18.0s cold subprocess).
- Both stages produce correct output: the linked binary runs and prints expected results.
- No BTA (kotlin-build-tools-api) equivalent exists for native — the daemon must invoke K2Native reflectively via `CLICompiler.exec(PrintStream, String[])`.
- Recommendation: **proceed to implementation**. The JVM daemon architecture (ADR 0016) can be extended with a native compilation backend that hosts K2Native in the same persistent JVM.

## §1 Feasibility

K2Native (`org.jetbrains.kotlin.cli.bc.K2Native`) extends `CLICompiler`, which exposes `exec(PrintStream, String[]): ExitCode`. This is the same class hierarchy as `K2JVMCompiler`.

The prototype loads `kotlin-native-compiler-embeddable.jar` (the single jar shipped with Kotlin/Native at `$KONAN_HOME/konan/lib/`) and calls `exec()` repeatedly with the same arguments. Required JVM properties: `-Dkonan.home=$KONAN_HOME`.

All invocations returned `ExitCode.OK`. The output klib and kexe are identical to subprocess-produced artifacts.

## §2 Measurements

### Stage 1: source → klib

| size | cold subprocess median (ms) | warm-hot best (ms) | speedup |
|------|----------------------------:|--------------------:|--------:|
| 1    | 3,379                       | 432                 | 7.8x    |
| 10   | 4,584                       | 620                 | 7.4x    |
| 25   | 5,137                       | 2,757               | 1.9x    |
| 50   | 6,220                       | 3,796               | 1.6x    |

The warm JVM eliminates ~3s of fixed startup cost. At small fixture sizes this dominates; at 50 files the compilation work itself is ~4s so the relative gain shrinks.

JIT warmup is visible: run 2 is slower than runs 3–4 (e.g. native-1: 673 → 432 → 458ms).

### Stage 2: klib → kexe (native-10)

| mode | wall (ms) |
|------|----------:|
| cold subprocess | 17,987 |
| warm-cold (1st in JVM) | 16,243 |
| warm-hot (2nd in JVM) | 11,629 |

Stage 2 is link-dominated (LLVM backend), so the JVM warmup benefit is smaller but still meaningful: **6.4s saved** per link on native-10.

### Correctness

Binary output from reflective build: `bench -764012367 229 577029334 23` — matches subprocess-built binary.

## §3 Architecture implications

### What works (reuse from ADR 0016)

- **Wire protocol**: `Message.Compile` / `CompileResult` over Unix domain socket — no change needed. The daemon dispatches to K2Native instead of BTA based on a target discriminator.
- **Socket lifecycle**: spawn-on-demand, idle timeout, max-compiles/heap watermark — all applicable.
- **Fallback**: `FallbackCompilerBackend` pattern (daemon → subprocess) works unchanged.

### What differs from JVM daemon

| Concern | JVM daemon (current) | Native daemon (proposed) |
|---------|---------------------|-------------------------|
| Compiler entry | BTA `CompilationService` (structured API) | `K2Native.exec(PrintStream, String[])` (CLI API) |
| Incremental compilation | BTA-managed, daemon-internal | Not available via BTA; would need `-Xenable-incremental-compilation` flag |
| Diagnostics | BTA provides structured errors | Must parse stderr (same as subprocess path) |
| Classpath | `kotlin-compiler-embeddable.jar` | `kotlin-native-compiler-embeddable.jar` |
| System property | none | `-Dkonan.home` required |

### Key risk: `-Dkonan.home` is JVM-global

`konan.home` is read via `System.getProperty()`. If the JVM hosts both JVM and native daemon, the property must either be set once at startup (requiring the native toolchain path at daemon launch time) or use classloader isolation. The simplest path: **separate daemon processes** for JVM and native, each with its own jar and system properties.

### Two-stage build

kolt's native build is two stages (library + link). Both stages call `konanc` with different `-p` flags. The daemon can handle both — the prototype confirmed this. The wire protocol's `Message.Compile` already carries args generically.

## §4 Blockers and risks

1. **No BTA for native**: the daemon must use the CLI reflection path. This means no structured incremental compilation API — IC would need `-Xenable-incremental-compilation` as a CLI flag, with cache management in the daemon or client.
2. **API stability**: `K2Native` and `CLICompiler.exec()` are internal APIs. JetBrains may change them between Kotlin versions. Mitigation: pin to specific Kotlin versions (already done per ADR 0022).
3. **State leakage at scale**: the prototype tested 4 invocations. Production daemon may run 100+ compiles. Need a longer-running stress test before shipping.
4. **Memory**: `kotlin-native-compiler-embeddable.jar` is 60MB. Combined with LLVM backend memory usage, the daemon may need `-Xmx4G` or higher.

## §5 Recommendation

**Proceed to implementation.** The feasibility question is answered: K2Native runs repeatedly in a persistent JVM with correct output and significant speedup.

Implementation path:
1. Add a native compilation backend to the existing daemon jar, or ship a separate `kolt-native-daemon` jar.
2. Use `K2Native.exec()` reflectively, same pattern as the prototype.
3. Parse `konanc` stderr for diagnostics (reuse existing `DiagnosticParser` patterns).
4. Start with a separate daemon process (avoids `-Dkonan.home` conflict with JVM daemon).
5. Defer native IC integration to a follow-up — the daemon speedup alone is worth shipping.

## Raw data

See `results-2026-04-19.md` in this directory.
