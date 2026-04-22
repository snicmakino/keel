---
status: accepted
date: 2026-04-13
---

# ADR 0014: Compile Kotlin/Native via two-stage library → link

## Summary

- Every native compilation splits into Stage 1 (`konanc -p library -nopack`, carries `-Xplugin`) and Stage 2 (`konanc -p program -Xinclude=...`). (§1)
- Four pure command builders in `Builder.kt` cover build and test paths: `nativeLibraryCommand`, `nativeLinkCommand`, `nativeTestLibraryCommand`, `nativeTestLinkCommand`. (§1)
- `-generate-test-runner` lives on the Stage 2 link command; it discovers `@Test` classes from the klib included via `-Xinclude`. (§2)
- The link stage repeats `-l <klib>` for every transitive external dependency; only the project klib uses `-Xinclude`. (§3)
- Plugin jars are fetched from Maven Central by `kolt.resolve.PluginJarFetcher`; the kotlinc sidecar is no longer consulted (#65). (§4)
- `BuildState` tracks the final kexe mtime only; the intermediate klib is not cached between stages. (§5)

## Context and Problem Statement

Issue #62 added Kotlin compiler plugin support (`serialization`, `allopen`, `noarg`) to the native build path. The initial implementation passed `-Xplugin=<jar>` to the existing single-step `konanc -p program` invocation in `doNativeBuild` and `doNativeTest`. Builds succeeded and konanc accepted the flag without complaint — but plugin registrars never ran. `@Serializable` was treated as a plain annotation, `Foo.serializer()` was never generated, and the intermediate klib contained no serializer symbols. The same behaviour reproduced across konanc 2.1.0 and 2.3.20, the kotlinc-distribution plugin jars and the `-embeddable` variants from Maven Central, and both `-Xplugin=` and `-Xcompiler-plugin=`. The plugin jars' `META-INF/services/` SPI entries were correct; the konanc wrapper passes `-Xplugin` to the underlying JVM process unmodified.

The Kotlin Gradle plugin runs the same plugin jar via the same flag against the same konanc binary, and the plugin does run. The difference is structural: KGP separates `compileKotlinLinuxX64` (`-produce library`) from `linkDebugExecutableLinuxX64`. Reproducing that two-stage shape from a shell made the plugin run:

```
konanc -p library -nopack -Xplugin=<jar> -l <deps> -o build/<name>-klib <sources>
konanc -p program -l <deps> -Xinclude=build/<name>-klib -o build/<name>
```

Stage 1 emits a klib whose IR already contains plugin-generated members. Stage 2 pulls the klib's IR via `-Xinclude`; the plugin is not needed at Stage 2 because IR transformation is complete. A single-step `-p program` against konanc 2.1.0/2.3.20 silently skips the plugin registrar pipeline. This is treated as a fixed toolchain cost; no upstream issue was filed.

ADR 0013 adopted the single-step α approach and explicitly deferred two-stage β. The trigger for reverting is not #59 (incremental test caching) but #62, and the change must apply to both `doNativeBuild` and `doNativeTest`.

## Decision Drivers

- Compiler plugins (`@Serializable`, `allopen`, `noarg`) must actually run on the native path
- Self-hosting kolt with kolt (#61) requires native plugin support (`kotlinx.serialization` for the lockfile)
- Symmetry between build and test paths
- Structural alignment with KGP for better toolchain interoperability

## Decision Outcome

Chosen option: **two-stage library → link for all native paths**, because the single-stage shape is silently broken for plugins and the two-stage workaround matches KGP's existing production shape.

### §1 Command builders

Four pure functions in `Builder.kt`, wired through `BuildCommands.kt`:

| Function | Stage | Role |
|---|---|---|
| `nativeLibraryCommand` | 1 | `konanc -p library -nopack` on `config.sources`, carrying `-Xplugin` args. Output: `build/<name>-klib/`. |
| `nativeLinkCommand` | 2 | `konanc -p program -Xinclude=build/<name>-klib`. Output: `build/<name>.kexe`. |
| `nativeTestLibraryCommand` | 1 | `konanc -p library -nopack` on `config.sources + config.testSources`. Output: `build/<name>-test-klib/`. |
| `nativeTestLinkCommand` | 2 | `konanc -p program -generate-test-runner -Xinclude=build/<name>-test-klib`. Output: `build/<name>-test.kexe`. |

### §2 Test runner placement

`-generate-test-runner` lives on Stage 2 (`nativeTestLinkCommand`), not the library stage. The synthesised runner discovers `@Test` classes from the klib included via `-Xinclude`. Verified end-to-end: `kolt.test` assertions pass under the two-stage shape.

**Note (ADR 0015):** This ADR's discussion originally referenced `nativeEntryPoint()` / `needsNativeEntryPointWarning()` as live code on the link path. Those helpers were deleted by ADR 0015, which redefined `config.main` as a Kotlin function FQN. `nativeLinkCommand` now emits `-e config.main` directly. The core two-stage decision is unaffected.

### §3 Dependency split at the link stage

`-Xinclude` inlines the project klib's IR into the final link unit. External references (e.g. calls from plugin-generated serializer code into `kotlinx-serialization-core`) are not in the project klib's IR and must still appear on `-l`. The link stage therefore repeats `-l <klib>` for every transitive external dependency; only the project klib uses `-Xinclude`.

### §4 Plugin jar resolution

`resolvePluginArgs` (in `PluginSupport.kt`) is invoked after the `isBuildUpToDate` check in `doNativeBuild`, so cached builds skip plugin resolution.

Plugin jars are fetched by `kolt.resolve.PluginJarFetcher` directly from Maven Central into `~/.kolt/cache` (#65). The kotlinc sidecar is not consulted; `doNativeBuild` / `doNativeTest` no longer call `ensureKotlincBin`. Native builds with plugins carry zero kotlinc dependency.

### §5 Intermediate klib layout

Stage 1 uses `-nopack` and writes an unpacked klib directory at `build/<name>-klib/`. `-Xinclude` accepts the directory path directly. `BuildState` tracks the final kexe mtime only; the intermediate klib is regenerated on every non-cached build. The `-klib` suffix keeps the intermediate distinct from the sibling `.kexe` without clashing.

### Consequences

**Positive**
- Compiler plugins actually run on native. `kolt build` + `kolt run` round-trips a `@Serializable` data class through JSON on `target = "native"` (verified via `native-serialization` kolt-examples project).
- ADR 0013's structural asymmetry between build and test paths is resolved: both `doNativeBuild` and `doNativeTest` share the same library → link shape.
- Closer to KGP's shape; konanc bug reports map more directly onto KGP's issue tracker.
- Removes the structural obstacle to incremental test caching (#59): a future optimisation can reuse a cached `build/<name>-klib` across `kolt build` and `kolt test`, letting test-only edits skip main-source recompilation.

**Negative**
- Two konanc invocations per native build, even for plugin-less projects. Overhead was not measured; KGP ships the same shape to all users.
- `BuildState` does not cache between stages: the intermediate klib is regenerated on every non-cached build.

### Confirmation

End-to-end: `kolt build` on a `target = "native"` project with `kotlinx.serialization` plugin enabled produces a binary that serialises and deserialises correctly. `kolt test` on the same project exits zero with passing tests.

## Alternatives Considered

1. **Keep α for `kolt test`; apply β only to the build path.** Rejected: the plugin problem affects `doNativeTest` equally, and split-path maintenance erases the symmetry benefit.
2. **Drop plugin support on native; tell users to target JVM for serialization-heavy code.** Rejected: kolt self-hosting (#61) requires native plugin support for `kotlinx.serialization` (lockfile). Plugin support is a prerequisite, not optional.
3. **Wait for an upstream konanc fix.** Rejected: no upstream issue was found tracking this, filing one gives no timeline, and the library → link workaround already works and matches KGP.
4. **Pack the intermediate klib (`build/<name>.klib`) instead of `-nopack`.** Rejected: `-nopack` is cheaper (no zip work), `-Xinclude` accepts either form, and the klib is a build-local artifact.

## Related

- ADR 0013 — superseded by this ADR; its α single-step approach is replaced by two-stage library → link.
- ADR 0012 — unchanged in decision but demoted in scope: `nativeEntryPoint()` is no longer on the compile path (deleted by ADR 0015).
- #62 — original issue surfacing the konanc plugin loading bug
- #61 — self-host kolt with kolt; the feature gate this ADR unblocks
- #65 — plugin jars from Maven Central via `PluginJarFetcher`, dropping the kotlinc sidecar
- #66 — implementation PR
