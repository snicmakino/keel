# ADR 0020: kolt-compiler-daemon scope is compilation-only

## Status

Accepted (2026-04-15).

## Context

ADR 0016 introduced `kolt-compiler-daemon`, a sidecar JVM that keeps
`kotlin-compiler-embeddable` warm across `kolt build` invocations so
the per-build JVM start + compiler warmup cost (~2.5 s) is paid once
instead of every build. ADR 0019 extended the same daemon with
incremental compilation via `kotlin-build-tools-api`. Both ADRs treat
the daemon as the execution vehicle for `kotlinc`, nothing else.

As kolt grows, there will be pressure to add features that also want
a warm JVM:

- build lifecycle hooks (pre/post-build, pre-test, …) executing a
  user-supplied jar
- linters / formatters running against project sources
- code generators driven off `kolt.toml`
- future "kolt watch" style long-running clients

Each of these has the same apparent argument: "the daemon is already
a warm JVM, let it run our jar too and save another ~0.7 s of JVM
startup". Taken individually each addition is small. Taken together
they are how Gradle got to where it is today: the Gradle daemon began
life as a build-script evaluator, absorbed task execution, then
configuration, then daemon-side plugin loading, then test worker
management, until "the Gradle daemon" became an umbrella for roughly
every JVM-side concern in the build. The resulting process is hard
to reason about, hard to cold-start, and hard to isolate failures in.
kolt's whole reason to exist is the opposite of that trajectory.

This ADR records the scope boundary explicitly so the next time
someone (including a future Claude session) reaches for "just run
this in the daemon", there is a written answer.

## Decision

### 1. `kolt-compiler-daemon` runs `kotlinc` and nothing else

The daemon's responsibility is: accept a `Message.Compile`, drive
`kotlin-compiler-embeddable` (directly per ADR 0016, or through
`kotlin-build-tools-api` per ADR 0019), return the result. That is
the entire charter.

Specifically **out of scope** for `kolt-compiler-daemon`:

- executing user-supplied jars (hooks, code generators, custom tasks)
- running test binaries (test execution stays on the native client's
  `java -jar junit-platform-console-standalone.jar` path)
- linters, formatters, static analysis
- file watching, daemon-side source indexing, LSP responsibilities
- any command that is not a compile

A proposal to add any of the above to `kolt-compiler-daemon` is
rejected by default under this ADR. Overriding it requires a new ADR
that supersedes this one, with explicit measurements showing the
warm-JVM saving is real and no comparable alternative exists.

### 2. If a second warm-JVM use case ships, it gets a new daemon

When a feature genuinely needs a warm JVM for non-compile work — the
leading candidate today is jar-based build hooks — it is a new
process, not a new responsibility on `kolt-compiler-daemon`. Two
small daemons with disjoint responsibilities are strictly cheaper to
reason about than one daemon with a growing responsibility list,
even if the total RSS is slightly higher.

A new daemon would get:

- its own socket path under `~/.kolt/daemon/<name>/`
- its own lifecycle (spawn, health check, stop)
- its own wire protocol, versioned independently of
  `Message.Compile`
- its own fallback path, analogous to `FallbackCompilerBackend`
  (ADR 0016 §5), so its absence never breaks a build

The native client orchestrates which daemon handles which work. The
daemons never talk to each other.

### 3. Rename path reserved, not committed

If a future ADR decides to fold hook execution (or another non-compile
warm-JVM use case) into the *existing* compiler daemon process —
instead of creating a second daemon per §2 — that decision must also
rename the process to `kolt-jvm-daemon`. The rename is load-bearing:
the name is how readers, logs, socket paths, and config fields
communicate the daemon's charter, and silently widening
"compiler-daemon" to cover non-compilation work would reintroduce
exactly the ambiguity this ADR exists to prevent.

This ADR does **not** schedule such a rename. No code change, no
socket path change, no user-facing rename is implied today. The
rename is a *conditional obligation* attached to a future decision
that has not been made. As of 2026-04-15 the preferred shape is §2
(a separate daemon); §3 exists so that the alternative path cannot
be taken cheaply and silently.

### 4. `~/.kolt/tools/` jar cache stays outside the daemon

The existing `~/.kolt/tools/` cache (used today for
`junit-platform-console-standalone`) is a *native-client* concern:
it is populated and consulted by the native process before it spawns
whatever JVM will run the jar. This ADR does not change that.

If a future warm-JVM use case (hooks, per §2) wants pre-loaded jars,
its own daemon is responsible for loading them. The jar artifacts
themselves continue to live in `~/.kolt/tools/` and to be fetched by
the native client's existing dependency-resolution machinery. The
tools cache is shared infrastructure; the warm JVM that eventually
runs the jar is not.

## Consequences

### Positive

- **Gradle's trajectory is explicitly rejected.** The rule "the
  compiler daemon compiles, full stop" is short enough to quote in a
  code review. "Does this belong in the compiler daemon?" has a
  default answer.
- **Failure blast radius stays small.** A crash, OOM, or classloader
  leak in the compiler daemon only kills compilation. Hooks, test
  execution, and formatters are unaffected because they are not
  running in the same process.
- **Wire protocol stays narrow.** ADR 0016's `Message.Compile` does
  not grow fields for non-compile work. ADR 0019 §4's "no wire
  change for IC" rule stays applicable: the wire is
  compile-compile-compile, and nothing else competes for it.
- **Second daemons, when added, are cheap to think about.** Each new
  daemon is its own small thing with its own socket and its own
  charter, instead of a conditional branch inside a process that
  does several unrelated jobs.

### Negative

- **Some JVM startup cost is paid more than once.** If hooks
  eventually get a second daemon (§2), a user running `kolt build`
  pays one compiler-daemon warmup and one hook-daemon warmup instead
  of one shared warmup. The warmups are concurrent, so wall-clock
  cost is ~max, not ~sum, but RSS is additive.
- **The rename door in §3 is a foot-gun if forgotten.** Someone
  could land a "small" hook-execution change inside
  `kolt-compiler-daemon/` without writing a superseding ADR or
  renaming the process. Mitigation: this ADR is the source of truth;
  code review against it is the check.
- **Running two daemons complicates `kolt stop`.** Issue #107 is
  currently scoped against a single daemon. If §2 fires, `kolt stop`
  must learn to enumerate all kolt daemons under `~/.kolt/daemon/`
  and shut each of them down. This is a cheap addition but it is an
  addition.

### Neutral

- **`~/.kolt/tools/` is declared native-client infrastructure.**
  §4 records a fact about the current implementation (the native
  client manages the tools cache) rather than changing anything. It
  is written down so that a future "put the tools cache behind the
  daemon" suggestion has a written counter.

## Alternatives Considered

1. **Let `kolt-compiler-daemon` grow.** Allow hook execution, test
   execution, and whatever else needs a warm JVM into the same
   process because "it's already warm". Rejected: this is exactly
   the Gradle-daemon trajectory. The individual increments are
   small; the cumulative effect is a component whose charter is
   "JVM stuff", which is not a charter.

2. **Rename to `kolt-jvm-daemon` now, even without adding
   non-compile work.** Preemptively broaden the name so future
   additions do not need a rename. Rejected: the rename is a
   promise. Renaming without a concrete second use case commits the
   project to a broader charter in docs, logs, and socket paths
   before there is a reason. If the concrete use case never
   materializes, we are stuck explaining why a "jvm-daemon" only
   does one thing.

3. **One daemon, multiple wire-protocol channels.** Keep a single
   process but give it separate message families for compile,
   hooks, etc. Rejected: this is (1) with extra ceremony. The
   failure modes still share a process, the classloader state still
   accumulates, and the wire protocol now has several independent
   schemas evolving inside one version namespace.

4. **No daemon at all for non-compile work.** Hooks and anything
   else that needs a JVM spawn a fresh JVM each time they run.
   Accepted as the *default* for anything that does not have a
   measured reason to want a warm JVM. This ADR does not force a
   second daemon into existence; §2 is the branch taken only when a
   concrete use case proves a fresh JVM is too slow.

## Related

- ADR 0016 — warm JVM compiler daemon (what this ADR scopes)
- ADR 0019 — incremental compilation via kotlin-build-tools-api
  (extends the daemon's *compile* responsibility, stays within
  this ADR's boundary)
- ADR 0021 — kolt does not ship a plugin system (the sibling
  decision this ADR complements)
- Issue #107 — `kolt stop` command (scope expands if §2 fires)
