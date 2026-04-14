# ADR 0017: Bootstrap JDK provisioning for the compiler daemon

## Status

Accepted (2026-04-14) as part of #14 PR3. The `kolt-compiler-daemon`
has its own JDK slot independent of the user's `kolt.toml [build] jdk`.
`BOOTSTRAP_JDK_VERSION` and the `resolveBootstrapJavaBin` helper
landed in PR3 S5.5; the fallback wiring
(`DaemonPreconditionError.BootstrapJdkInstallFailed` →
`resolveCompilerBackend` skips the daemon wrapper and returns a plain
`SubprocessCompilerBackend` with a one-line warning) landed in PR3 S7
together with the daemon going default-on.

Auto-install landed in issue #93: `installJdkToolchain`,
`installKotlincToolchain`, `installKonancToolchain`, and their
`ensure*` wrappers in `ToolchainManager.kt` now return
`Result<_, ToolchainError>` instead of calling `exitProcess`, so
`ensureBootstrapJavaBin(paths)` can synchronously download the pinned
JDK on first use while keeping the daemon bring-up path non-load-
bearing — a network failure surfaces as
`BootstrapJdkInstallFailed(jdkInstallDir, cause)` and the build
silently falls back to the subprocess compile path, honouring the
ADR 0016 §5 invariant.

## Context

`DaemonCompilerBackend` (ADR 0016) spawns a helper JVM with
`<java> -jar kolt-compiler-daemon-all.jar --socket <path> --compiler-jars <cp>`.
Two questions arise the first time a user runs `kolt build` on a
machine that does not yet have a suitable JDK:

1. **Whose JDK does the daemon run on?** The user's project-pinned
   JDK from `kolt.toml` (e.g. JDK 11 for a legacy library), the host
   system JDK (whatever `java` is on `PATH`), or a separate kolt-owned
   JDK?
2. **If kolt owns it, how does kolt get it?** Is provisioning
   automatic and synchronous, opt-in, or a manual step?

Until #14 Phase A, `kolt build` only needed the project JDK for the
final run step; compilation itself shelled out to `kotlinc`, which
already downloaded its own JRE at toolchain install time. The daemon
changes that: the daemon is a long-lived process that kolt itself
spawns, and if it crashes or refuses to start the user sees "slow
builds" with no obvious root cause. UX of the first-run experience
therefore dominates the decision.

The options considered:

- **Use the project JDK (`kolt.toml [build] jdk`).** The daemon runs
  on whatever the user's project is configured for. Pros: no extra
  download, no separate toolchain slot. Cons: a project pinned to
  JDK 8 cannot run the compiler daemon at all (Kotlin compiler
  requires JDK 11+ from Kotlin 1.9 and JDK 17+ from 2.x). Changing
  the project JDK cold-boots the daemon. "My build is slow after
  bumping jdk" is a nasty recurring support ticket.
- **Use the host system JDK (`PATH` lookup).** The daemon runs on
  whatever `java` happens to be on the user's PATH. Pros: zero
  download, zero configuration. Cons: kolt has no control over the
  version (could be JDK 6 from a Homebrew install), no control over
  the binary's integrity, and no way to surface "this JDK is too
  old" cleanly. Breaks kolt's "lightweight but reproducible" pitch.
- **Bundle a JDK into the kolt release artifact.** Ship the JDK in
  the kolt tarball. Pros: first run is instant. Cons: kolt's native
  binary is currently ~10 MB; a bundled JDK turns that into ~100 MB.
  Also ties the kolt release cadence to JDK security patches in a
  way we cannot honour without a lot of release engineering.
- **Auto-provision a pinned JDK on first use.** kolt owns a
  dedicated slot under `~/.kolt/toolchains/jdk/<bootstrap>/` and
  downloads it synchronously the first time the daemon needs to
  spawn. Pros: reproducible (pinned version), small initial
  download, same pattern kolt already uses for `kotlinc` and
  `konanc` (ADR 0009). Cons: first `kolt build` is gated on a ~200
  MB download, and an offline first run fails.

The daemon is already fallback-safe — any startup failure drops
back to the subprocess compile path (ADR 0016 §5 / §Consequences).
That means an offline first-run does not break the build; the user
sees the old 8-second clean build until the next time they are
online, and once the bootstrap JDK lands the daemon takes over.
This makes the "auto-provision" option's one real drawback bearable.

## Decision

kolt reserves a dedicated bootstrap JDK slot for the daemon, pinned
in the kolt binary as `BOOTSTRAP_JDK_VERSION` in
`kolt.build.daemon.BootstrapJdk`. Phase A pins the constant to
`"21"` (Adoptium `latest/21`, matching the format the existing
`installJdkToolchain` already consumes), and lives under
`~/.kolt/toolchains/jdk/21/` — same namespace as user-requested
JDKs, so a project that already pins JDK 21 shares the install for
free.

The daemon bring-up path calls `ensureBootstrapJavaBin(paths)`, which
auto-installs the pinned JDK under
`~/.kolt/toolchains/jdk/<BOOTSTRAP_JDK_VERSION>/` the first time it
is needed, using the same `installJdkToolchain` code path the user
sees for explicit `kolt toolchain install`. The daemon being "never
load-bearing for correctness" (ADR 0016 §5) is preserved because
every failure on that path (download, checksum, extract) surfaces
as `BootstrapJdkError` → `BootstrapJdkInstallFailed`, and the build
degrades to the subprocess compile path with a one-line warning.

A read-only sibling, `resolveBootstrapJavaBin(paths)`, is kept next
to `ensureBootstrapJavaBin` for diagnostic callers that want to
probe state without triggering a download.

**First-run UX today**: the first `kolt build` after a clean install
pauses to download the bootstrap JDK once (progress reuses the
existing `downloading jdk 21…` wording), then the daemon activates
immediately. An offline first run falls back to the subprocess
compile path silently and retries the install on the next online
build. Users do **not** need a separate `kolt toolchain install`
step for the bootstrap JDK.

The bootstrap JDK is deliberately **not** wired through `kolt.toml`.
Users cannot change `BOOTSTRAP_JDK_VERSION` per project. The daemon
is a kolt internal; giving users a knob to downgrade the daemon's
JDK invites a failure mode where a project pins a JDK too old to
run modern Kotlin compilers and blames kolt for slow builds.

### Pinning format trade-off

The current pin uses Adoptium's `latest/<feature>` endpoint, which
tracks "whatever 21 GA is today" rather than an exact version like
`21.0.5+11`. This is a conscious trade-off: the existing
`installJdkToolchain` in `ToolchainManager` uses that endpoint
shape, checksum-verifies what it downloads against the same snapshot,
and upgrading to a fully qualified version (`/v3/binary/version/...`)
is a larger refactor than PR3 can absorb. The reproducibility gap is
that two machines installing on different days can end up with
different point releases of JDK 21, but both will satisfy the
daemon's requirements identically. Tightening the pin to an exact
version is follow-up work once the `version/...` endpoint is
wired into the toolchain code.

## Consequences

### Positive

- **Daemon JDK is decoupled from the project JDK.** A project pinned
  to JDK 11 still gets the warm-compiler speedup.
- **Reproducible enough.** Every machine running the same kolt build
  gets the same major/minor JDK family, and a kolt release can bump
  the bootstrap version once for everyone by shipping a new
  `BOOTSTRAP_JDK_VERSION`.
- **Consistent toolchain UX.** The download uses the same
  `installJdkToolchain` code path users already see for `kolt install
  jdk`. There is no parallel provisioning mechanism.
- **Shared cache on alignment.** If the user's project JDK and the
  bootstrap happen to be the same version, there is exactly one
  install under `~/.kolt/toolchains/jdk/21/`.

### Negative

- **First run pauses to download the bootstrap JDK.** With
  auto-install, the first `kolt build` on a clean machine is gated
  on a ~200 MB download before the daemon can start. Acceptable
  because (a) the download reuses the existing toolchain install
  progress wording, and (b) an offline first run silently
  degrades to the subprocess compile path — the same ~8 s clean
  build as pre-daemon kolt — and the next online run installs and
  activates the daemon without any user intervention.
- **Bootstrap version is a new thing to keep current.** Kolt release
  notes now have to cover "which JDK the daemon runs on this
  release". If the pinned bootstrap JDK falls behind in security
  patches, every kolt user downloading afterwards inherits the lag.
- **Feature-version pin, not exact-version pin.** Two kolt users
  running the same kolt version can end up with different JDK point
  releases. Acceptable for now, but means "kolt version X uses JDK
  21.0.5" is not a statement we can make without further work.

### Neutral

- **No `kolt.toml` knob for the daemon JDK.** Users cannot override
  `BOOTSTRAP_JDK_VERSION` per project. If someone surfaces a
  legitimate reason ("we sandbox all downloaded binaries and need
  to point kolt at /opt/our-jdks"), we can add an opt-in escape
  hatch in a later ADR — but not preemptively.

## Alternatives Considered

1. **Use the project JDK.** Rejected: forces the daemon to inherit
   whatever the user pinned for their app, which may be too old.
2. **Use the host JDK on PATH.** Rejected: no version control, no
   integrity check, doesn't match the kolt toolchain philosophy.
3. **Bundle a JDK in the kolt release.** Rejected: blows up the
   release artifact size and couples the kolt release cadence to
   JDK security patches.
4. **Exact-version pin from day one.** Rejected for PR3 scope: the
   Adoptium exact-version endpoint requires a second URL pattern
   that the existing `installJdkToolchain` does not know about.
   Tracked as a follow-up under the daemon epic.
5. **Auto-install from day one.** Considered and rejected for PR3:
   the existing `installJdkToolchain` / `ensureJdkBins` exited the
   kolt process on any download or checksum failure, which would
   have broken the daemon-is-never-load-bearing invariant
   (ADR 0016 §5). Landed in follow-up issue #93 by refactoring
   those paths (and every other `exitProcess` call in
   `ToolchainManager.kt`) to return `Result<_, ToolchainError>`, so
   `ensureBootstrapJavaBin` can now install synchronously and
   downgrade any failure to a fallback warning.

## Related

- #14 — parent issue (Kotlin Compiler Daemon integration)
- ADR 0016 — the warm-daemon architecture this ADR supports
- ADR 0009 — the existing auto-install-toolchain pattern that this
  ADR follows
- `BootstrapJdk.kt` in `kolt.build.daemon` — the pin and the
  `ensureBootstrapJavaBin` helper
- `DaemonCompilerBackend.kt` — the consumer that receives the
  bootstrap `java` binary path in its `javaBin` constructor
  parameter
