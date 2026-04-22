---
status: accepted
date: 2026-04-15
---

# ADR 0021: kolt does not ship a plugin system

## Summary

- kolt will not provide a plugin API, plugin loader, or plugin dependency-resolution path for third-party code executed at build time. (§1)
- Kotlin/Native binaries cannot host JVM code in-process; the only warm JVM kolt owns is reserved for compilation by ADR 0020. (§2)
- kolt's declared scope (JVM CLI tools and server-side Kotlin binaries) is closed and small; it does not carry Amper-level scope. (§3)
- A plugin API is a forever contract; any proposal to add one is rejected by default and requires a successor ADR with evidence that the three structural reasons no longer apply. (§4)
- This ADR does not prohibit declarative extension points (e.g. lifecycle hooks) that run user-supplied jars as subprocesses — those do not host third-party code in-process. (§5)

## Context and Problem Statement

`docs/design.md` listed "プラグインシステム" as out of scope since Phase 1, with a rationale that reads as "not yet" rather than "not this tool." After more experience with kolt's shape and with how Gradle and Amper approached extensibility, the honest position is "not this shape, structurally." This ADR upgrades the scope-out note to a load-bearing architectural decision.

The decision matters now because ADR 0020 drew a hard boundary around the compiler daemon's charter. A plugin system would be the natural thing to wire into that daemon, and that boundary is easier to hold with a sibling ADR ruling out plugin ambitions entirely. User pressure for "a way to run my own code as part of the build" is also accumulating; without a decision every request restarts the same debate from first principles.

## Decision Drivers

- Internal changes (renames, data-structure reshaping, moving work between native client and daemon) must stay internal — no downstream plugin migration cost.
- ADR 0020's daemon boundary ("compilation only") must have a sibling ruling out the complementary plugin loader use case.
- The native-binary architecture (no in-process JVM) must be an asset, not a liability that forces workarounds.

## Decision Outcome

Chosen option: **no plugin system**, because all three structural reasons in §2–§4 are independent and each alone is sufficient to reject it.

### §1 What this ADR prohibits

kolt does not and will not provide:

- A plugin API (interfaces, SPI, annotation-driven extension points) that third-party code implements and kolt loads at build time.
- A plugin loader inside `kolt-compiler-daemon`, `kolt-jvm-daemon` (were it to exist), or the native client.
- A `[plugins]` section in `kolt.toml` naming arbitrary extension libraries by Maven coordinate for kolt to execute.
- A dependency-resolution path specifically for "plugin dependencies."
- Plugin metadata on the wire between the native client and any kolt-owned daemon.

A proposal to add any of the above is rejected by default. Overriding requires a new ADR that supersedes this one, with concrete evidence that the three reasons below no longer apply.

### §2 Structural reason A — Kotlin/Native cannot host JVM code in-process

kolt is a Kotlin/Native Linux x64 binary. A JVM-language plugin cannot be loaded into its address space; the native process has no JVM, and starting one on demand surrenders the startup-cost win kolt exists to provide. The only warm JVM kolt owns is `kolt-compiler-daemon`, and ADR 0020 reserves it for compilation. A plugin system would therefore either (a) spawn a fresh JVM per plugin invocation, losing the startup win, or (b) introduce a second long-running daemon whose charter is "run arbitrary plugin code" — a large new surface with a large new failure mode. Either is strictly worse than no plugin system.

### §3 Structural reason B — kolt's scope is closed and small

JetBrains' [Amper] adopted a plugin-based architecture because it targets Kotlin Multiplatform, Android, iOS, and server-side Kotlin simultaneously. That target matrix is too wide for any fixed built-in set. kolt's declared target is narrower: JVM CLI tools and server-side Kotlin binaries, with Kotlin/Native as a secondary target (Issue #16). For that scope the set of concerns — compile, test, run, dependency resolve, clean, init — is closed and small, and a built-in implementation of each is tractable.

[Amper]: https://github.com/JetBrains/amper

### §4 Structural reason C — a plugin API is a forever contract

Once third-party plugins exist, any change to the plugin API is a semver-breaking event for downstream authors kolt has never met. Gradle dedicates a non-trivial fraction of release-note real estate to plugin API deprecations and migration guides. kolt is a small project and cannot subsidise that cost. "No plugin system" means every internal change — renaming a class, reshaping a data structure, moving work between daemon and native client — stays internal.

### §5 What this ADR does not prohibit

This ADR does not prohibit:

- Declarative sections in `kolt.toml` (e.g. a hypothetical `[hooks]`) that invoke user-supplied jars kolt runs as subprocesses. Running a subprocess does not host third-party code in-process and does not commit kolt to a plugin SPI. Whether such a section ships is a separate design question.
- Internal modularisation. Splitting kolt's own source tree into subprojects (`kolt-compiler-daemon/` already exists) is an internal refactor.
- Future extensibility of any shape that does not reintroduce third-party code hosted in kolt's process, a plugin API kolt must preserve across versions, or a plugin dependency-resolution path.

This ADR closes the door on *third parties implementing a kolt-defined interface and kolt loading their code*. It does not close the door on *users telling kolt, declaratively, to run a specific jar at a specific lifecycle point*.

### Consequences

**Positive**
- Internal refactors, renames, and data-structure changes carry no downstream plugin migration cost.
- ADR 0020 ("daemon compiles, full stop") and this ADR together make the default answer to "can we run third-party JVM code inside the daemon?" an unambiguous no.
- The Kotlin/Native architecture stops being a liability — the inability to host an in-process JVM is the constraint that makes the narrow, stable internal shape credible.
- Extensibility discussions shrink to one scoped question ("what declarative extension point?") rather than the open-ended "how do we build a plugin system?"

**Negative**
- Users who need a custom compiler plugin (kapt, K2 FIR plugin, KSP processor) through kolt have no path until compile-time plugin support is designed separately as a feature of the compile pipeline. Those users stay on Gradle for now.
- Extensibility requests will keep arriving. This ADR routes them toward concrete declarative-section proposals (§5), but discussion will continue.

### Confirmation

Any proposal adding a plugin API, plugin loader, or plugin dependency-resolution path is rejected by reference to this ADR. Overriding requires a successor ADR citing concrete evidence that §2, §3, and §4 no longer apply.

## Alternatives considered

1. **Gradle-style plugin system (SPI, plugin dependency resolution, classloading inside the daemon).** Rejected for all three reasons in §2–§4: Kotlin/Native cannot host JVM plugins in-process, kolt's scope does not need Amper-level extensibility, and the forever-contract cost is net-negative for a small project.
2. **Amper-style plugin system (lighter, closer to a template system).** Rejected. Amper's plugin choice is justified by Amper's scope (Multiplatform + Android + iOS). Inheriting the machinery without the scope is a cost without the benefit.
3. **Leave the `docs/design.md` "安定したコア機能の確立が先" note as the de-facto answer.** Rejected. That framing implies plugins are the eventual direction, biasing near-term designs toward preserving plugin-friendliness. Writing the decision down explicitly removes that bias.
4. **In-daemon-only plugins (load plugin jars into `kolt-compiler-daemon`'s URLClassLoader).** Rejected. Violates ADR 0020 directly, and maximises blast radius — a buggy plugin can crash the compile path, corrupt incremental state, or leak classloader roots into the warm JVM (ADR 0016 §4's 30-minute idle restart).

## Related

- ADR 0016 — warm JVM compiler daemon (the only warm JVM kolt owns; this ADR rules out loading plugins into it)
- ADR 0019 — incremental compilation via kotlin-build-tools-api (the daemon's compile-surface extension)
- ADR 0020 — compiler daemon scope is compilation-only (sibling decision)
- `docs/design.md` — scope-out section updated to replace the Japanese rationale note with a pointer to this ADR
