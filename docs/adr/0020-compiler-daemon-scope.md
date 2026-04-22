---
status: accepted
date: 2026-04-15
---

# ADR 0020: kolt-compiler-daemon scope is compilation-only

## Summary

- `kolt-compiler-daemon` accepts `Message.Compile`, drives `kotlin-compiler-embeddable` or `kotlin-build-tools-api`, and returns the result. No other work belongs in this process. (§1)
- Any new warm-JVM use case gets a separate daemon with its own socket, lifecycle, wire protocol, and fallback path. Two small daemons with disjoint responsibilities are cheaper to reason about than one growing daemon. (§2)
- If a future decision folds non-compile work into the existing process, that decision must also rename the process. The rename is a conditional obligation, not a scheduled change. (§3)
- `~/.kolt/tools/` remains native-client infrastructure; the tools cache is not behind the daemon. (§4)

## Context and Problem Statement

ADR 0016 introduced `kolt-compiler-daemon` as the execution vehicle for `kotlinc`. ADR 0019 extended it with IC via `kotlin-build-tools-api`. Both treat the daemon as compiler-only.

As kolt grows, pressure will arise to add features that want a warm JVM: build lifecycle hooks, linters, code generators, a future `kolt watch`. Each individual addition looks small. Cumulatively they are how Gradle's daemon went from build-script evaluator to an umbrella for every JVM-side build concern — a process that is hard to reason about, hard to cold-start, and hard to isolate failures in. kolt exists to avoid that trajectory.

This ADR records the scope boundary so the next time someone (or a future agent session) reaches for "just run this in the daemon", there is a written answer.

## Decision Drivers

- Failure blast radius of compiler bugs must not touch hooks, test execution, or formatters.
- Wire protocol (`Message.Compile`) must not grow fields for non-compile work.
- Future daemons should each be simple enough to understand in isolation.

## Decision Outcome

Chosen: **compilation-only scope for `kolt-compiler-daemon`**, because the cost of a second daemon (slightly more RSS) is strictly less than the maintenance cost of a general-purpose JVM sidecar.

### §1 kolt-compiler-daemon runs kotlinc and nothing else

The daemon's charter: accept `Message.Compile`, drive `kotlin-compiler-embeddable` (ADR 0016) or `kotlin-build-tools-api` (ADR 0019), return the result.

Explicitly out of scope:

- executing user-supplied jars (hooks, code generators, custom tasks)
- running test binaries (test execution stays on the native client's `java -jar junit-platform-console-standalone.jar` path)
- linters, formatters, static analysis
- file watching, source indexing, LSP responsibilities
- any command that is not a compile

A proposal to add any of the above is rejected by default. Overriding requires a new ADR that supersedes this one, with measurements showing the warm-JVM saving is real and no comparable alternative exists.

### §2 New warm-JVM use cases get a new daemon

When a feature genuinely needs a warm JVM for non-compile work, it is a new process. A new daemon gets:

- its own socket path under `~/.kolt/daemon/<name>/`
- its own lifecycle (spawn, health check, stop)
- its own wire protocol, versioned independently of `Message.Compile`
- its own fallback path analogous to `FallbackCompilerBackend` (ADR 0016 §5)

The native client orchestrates which daemon handles which work. Daemons never talk to each other.

`kolt daemon stop` discovers running daemons by walking `~/.kolt/daemon/<projectHash>/`; each new daemon inherits stop for free with no hard-coded list. (Issue #107 is currently scoped for one daemon; §2 expanding adds a directory walk, not a new stop command.)

### §3 Rename path reserved, not committed

If a future ADR folds non-compile work into this process instead of creating a second daemon per §2, that decision must also rename the process to `kolt-jvm-daemon`. The name communicates charter — widening "compiler-daemon" silently to cover non-compilation work reintroduces the ambiguity this ADR prevents.

No code change, no socket path change, and no user-facing rename is implied today. The rename is a conditional obligation on a future decision that has not been made. The preferred shape remains §2.

### §4 ~/.kolt/tools/ is native-client infrastructure

`~/.kolt/tools/` (used today for `junit-platform-console-standalone`) is populated and consulted by the native process before spawning whatever JVM runs the jar. If a future warm-JVM use case (hooks, per §2) wants pre-loaded jars, its own daemon loads them; the tools artifacts continue to live in `~/.kolt/tools/` and are fetched by native-client dependency resolution.

## Consequences

**Positive**
- A compiler daemon crash or OOM only kills compilation; hooks, test execution, and formatters are unaffected.
- `Message.Compile` does not grow fields for non-compile work; ADR 0019 §4's "no wire change for IC" stays applicable.
- Each new daemon is its own bounded thing rather than a conditional branch inside a multi-responsibility process.
- "Does this belong in the compiler daemon?" has a default answer short enough to quote in a review.

**Negative**
- If hooks get a second daemon (§2), a build pays two daemon warmups instead of one. Warmups are concurrent so wall-clock cost is ~max, not ~sum, but RSS is additive (~200 MB per daemon).
- §3's rename obligation can be missed if the future PR author does not consult this ADR. Mitigation: this ADR is the source of truth; code review against it is the check.

### Confirmation

Enforced by review. A PR adding non-compile work to `kolt-compiler-daemon/` without a superseding ADR is rejected pointing at §1.

## Alternatives considered

1. **Let `kolt-compiler-daemon` grow.** Allow hooks, test execution, and whatever else needs a warm JVM. Rejected: this is the Gradle-daemon trajectory; individual increments are small, cumulative effect is a component whose charter is "JVM stuff".
2. **Rename to `kolt-jvm-daemon` now.** Preemptively broaden the name so future additions do not need a rename. Rejected: the rename is a promise; committing to a broader charter before there is a concrete second use case documents a future that may never materialise.
3. **One daemon, multiple wire-protocol channels.** Keep a single process with separate message families. Rejected: failure modes still share a process, classloader state still accumulates, and the wire protocol now has several independent schemas evolving inside one version namespace.
4. **No daemon for non-compile work.** Hooks and anything else that needs a JVM spawn a fresh JVM. Accepted as the default; §2 is taken only when a concrete use case proves a fresh JVM is too slow.

## Related

- ADR 0016 — JVM compiler daemon (what this ADR scopes)
- ADR 0019 — IC via `kotlin-build-tools-api` (extends the daemon's compile responsibility, stays within this ADR's boundary)
- ADR 0021 — kolt does not ship a plugin system (sibling decision)
- ADR 0026 — current daemon naming authority (see for new module names; this ADR's identifiers are preserved as historical record)
- ADR 0018 — distribution layout (daemon as IPC-only; context for what a second daemon would require in distribution terms)
- #107 — `kolt daemon stop` command (scope expands if §2 fires)
