# ADR 0021: kolt does not ship a plugin system

## Status

Accepted (2026-04-15).

## Context

`docs/design.md` has listed "プラグインシステム" under スコープ外 since
Phase 1, with the one-line rationale "拡張性は重要だが、安定したコア
機能の確立が先". That framing reads as "not yet" — as if a plugin
system is the obvious long-term direction and the only open question
is timing. After more experience with kolt's shape, with how other
Kotlin build tools (Gradle, Amper) have approached extensibility, and
with the practical consequences of kolt being a Kotlin/Native binary,
that framing is wrong. The honest position is closer to "not this
tool, not this shape". This ADR upgrades the scope-out note to a
load-bearing architectural decision, records the three reasons for
it, and makes clear what the decision does *not* say.

The decision matters now, not later, because:

- ADR 0016 (warm JVM compiler daemon) and ADR 0019 (incremental
  compilation) are building up a warm JVM inside `kolt-compiler-daemon`.
  A plugin system would be natural to wire into that daemon, and ADR
  0020 just drew a hard boundary around what the daemon is for. That
  boundary is easier to hold if there is a sibling ADR saying kolt
  does not have plugin ambitions in the first place.
- Real user pressure for "a way to run my own code as part of the
  build" is accumulating (build hooks, code generation, custom
  checks). Without a decision on the plugin shape, every one of
  those asks gets re-debated from first principles.

This ADR answers the shape question ("no plugin system") but
deliberately does **not** answer the companion question ("what is the
extensibility story, then?"). See §Non-goals.

## Decision

### 1. kolt will not ship a plugin system

Concretely, kolt does not and will not provide:

- a plugin API (interfaces, SPI, annotation-driven extension points)
  that third-party code implements and kolt loads at build time
- a plugin loader inside `kolt-compiler-daemon`,
  `kolt-jvm-daemon` (were it to exist), or the native client
- a `[plugins]` section in `kolt.toml` naming arbitrary extension
  libraries by Maven coordinate and having kolt execute them
- a dependency-resolution path specifically for "plugin
  dependencies" as distinct from "project dependencies"
- plugin metadata on the wire between the native client and any
  kolt-owned daemon

A proposal to add any of the above is rejected by default under this
ADR. Overriding it requires a new ADR that supersedes this one, with
concrete evidence that the three reasons in §2 no longer apply.

### 2. Why — three reasons, in order

**(a) Kotlin/Native binaries cannot host JVM code in-process.** kolt
itself is a Kotlin/Native Linux x64 binary. A JVM-language plugin
cannot be loaded into kolt's address space; the native process has no
JVM and starting one on demand is exactly the JVM startup cost kolt
exists to avoid. The only place kolt already has a warm JVM is
`kolt-compiler-daemon`, and ADR 0020 reserves that for compilation.
So a plugin system would either (i) spawn a fresh JVM per plugin
invocation, which surrenders the startup-cost win, or (ii) introduce
a second long-running daemon whose charter is "run arbitrary plugin
code", which is a large new surface with a large new failure mode.
Either choice is strictly worse than not having the system at all.

**(b) Amper chose plugins because its scope demanded them — kolt's
scope does not.** JetBrains' [Amper] moved toward a
plugin-based architecture because it targets Kotlin Multiplatform,
Android (AGP integration), iOS (Xcode integration), and server-side
Kotlin under one config. The *matrix* of targets, toolchains, and
build phases is too wide for any fixed set of built-ins. Plugins are
the only way to factor that without shipping a monolith. kolt's
declared target is narrower: JVM CLI tools and server-side Kotlin
binaries, with Kotlin/Native as a secondary target (Issue #16). For
that scope, the set of concerns — compile, test, run, dependency
resolve, clean, init — is closed and small, and a built-in
implementation of each is tractable. kolt is not Amper and should not
pretend to carry Amper's scope.

[Amper]: https://github.com/JetBrains/amper

**(c) Plugin systems are forever contracts with unbounded failure
modes.** Once third-party plugins exist, any change to the plugin
API is a semver-breaking event for an unknown number of downstream
authors kolt has never met. Gradle carries this cost: a non-trivial
fraction of Gradle release-note real estate is dedicated to plugin
API deprecations and migration guides. kolt is a small project and
cannot subsidize that cost. "No plugin system" means every internal
change — renaming a class, reshaping a data structure, moving work
between the daemon and the native client — stays internal. That
property is load-bearing for a one-to-few-person project.

### 3. Non-goals of this ADR

This ADR does **not**:

- declare that kolt users can never run their own code as part of a
  build. Build-lifecycle hooks (pre/post-build, pre-test) running a
  user-supplied jar or shell command are an open design area being
  discussed separately. "Not a plugin system" and "not extensible
  at all" are different statements.
- constrain what a future extensibility story can look like, *as
  long as* it does not reintroduce the shape this ADR rejects
  (third-party code hosted in-process inside kolt or
  `kolt-compiler-daemon`; a plugin API kolt must preserve across
  versions; a plugin dependency-resolution path).
- remove `kolt.toml`'s existing and future declarative sections.
  Adding a new declarative section (e.g., a hypothetical `[hooks]`)
  that invokes jars which kolt runs as subprocesses is not "a
  plugin system" in the sense this ADR rejects, because it does not
  host third-party code in kolt's process and does not commit kolt
  to a plugin SPI. If and when such a section is proposed, it is a
  fresh design question.
- forbid internal modularization. Splitting kolt's own source tree
  into subprojects (as `kolt-compiler-daemon/` already is) is an
  internal refactor, not a plugin system.

Put differently: this ADR closes the door on *third parties
implementing a kolt-defined interface and kolt loading their code*.
It does not close the door on *users telling kolt, declaratively,
to run a specific jar at a specific lifecycle point*.

## Consequences

### Positive

- **Internal changes stay internal.** Refactors, rename passes, and
  data-structure changes in kolt do not carry a downstream plugin
  migration cost, because there are no downstream plugin authors by
  construction. The contrast with Gradle is explicit and
  intentional.
- **ADR 0020 gets a sibling.** "The compiler daemon compiles, full
  stop" (0020) and "kolt has no plugin system" (this ADR) together
  make the default answer to "can we run this third-party JVM code
  inside the daemon" an unambiguous no, without having to relitigate
  either side per request.
- **The native-binary architecture stops being a liability.** Not
  being able to host a JVM in-process has been a framed as a
  limitation. Under this ADR, it is the *reason* kolt gets to have
  a narrow, stable internal shape. What looked like a limit is the
  constraint that makes the small-tool story credible.
- **Scope of user-facing extensibility discussions shrinks to one
  question.** "What declarative extension point do we want?" is a
  single, scoped design question (hooks, codegen, …). "How do we
  build a plugin system?" is an open-ended one. This ADR removes
  the second question so the first one can be answered.

### Negative

- **Some use cases have no good answer today.** A user who wants to
  run a custom compiler plugin (a kapt-style annotation processor,
  a K2 FIR plugin, a ksp processor) through kolt has no path until
  compile-time plugin support is designed separately — and that
  design will happen, if it happens at all, as a feature of the
  compile pipeline, not as a general plugin system. Users with that
  need today will stay on Gradle.
- **Extensibility requests will keep landing.** Closing the plugin
  door does not close user demand for "a way to run my own code".
  This ADR explicitly invites those requests to become concrete
  declarative-section proposals (per §3), but it does accept that
  more discussion will happen, not less.
- **The ADR is easy to misread as "kolt will never extend".** The
  §3 non-goals section exists specifically to head that off, but
  some fraction of readers will still quote this ADR as "kolt said
  no to extensibility" when it said no to a specific shape of
  extensibility. Mitigation: the non-goals stay load-bearing in any
  future edit of this ADR.

### Neutral

- **Hooks (or any other declarative extension) are not endorsed
  here.** §3 lists hooks as an example of a shape that is *not*
  what this ADR rejects, but that is a scoping clarification, not
  a commitment. Whether hooks ship is a separate decision tracked
  elsewhere.

## Alternatives Considered

1. **Adopt a plugin system modeled on Gradle's.** A plugin SPI,
   plugin dependency resolution, plugin classloading inside the
   daemon. Rejected for all three reasons in §2: Kotlin/Native
   can't host JVM plugins in-process, kolt's scope doesn't need
   Amper-level extensibility, and the forever-contract cost is
   strictly net-negative for a small project.

2. **Adopt a plugin system modeled on Amper's.** Amper's plugin
   story is lighter than Gradle's and is closer to a template
   system than a full SPI. Rejected anyway: Amper's plugin choice
   is justified by Amper's scope (Multiplatform + Android + iOS),
   which kolt does not share. Inheriting the plugin machinery
   without the scope that motivates it is a cost without the
   benefit.

3. **"We'll add plugins later."** Leave `docs/design.md`'s existing
   "安定したコア機能の確立が先" note as the de-facto answer.
   Rejected: that framing implies plugins are the eventual
   direction, which biases every near-term design toward
   preserving plugin-friendliness. Writing the decision down
   explicitly frees near-term designs from that implicit
   constraint.

4. **Ship a plugin system but keep it in-daemon-only.** Load
   plugin jars into `kolt-compiler-daemon`'s URLClassLoader and
   let them see the compiler. Rejected: it violates ADR 0020
   (daemon charter) directly, and it maximizes blast radius —
   a buggy plugin can crash the compile path, corrupt incremental
   state, or leak classloader roots into the warm JVM kolt is
   working hard to keep clean (ADR 0016 §4's 30-minute idle
   restart, etc.).

## Related

- ADR 0016 — warm JVM compiler daemon (the only warm JVM kolt
  currently owns; this ADR rules out loading plugins into it)
- ADR 0019 — incremental compilation via kotlin-build-tools-api
  (the daemon's compile-surface extension, which this ADR does
  not cover)
- ADR 0020 — compiler daemon scope is compilation-only (the
  sibling decision, covering the daemon's charter)
- `docs/design.md` — scope-out section (updated in the same
  change as this ADR to replace the "安定したコア機能の確立が先"
  note with a pointer to this ADR)
