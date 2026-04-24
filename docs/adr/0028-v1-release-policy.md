---
status: proposed
date: 2026-04-24
---

# ADR 0028: v1.0 release policy and RC window

## Summary

- v1.0 cut-over is gated by the `v1.0` GitHub milestone reaching zero
  open issues, followed by one or more `1.0.0-rc.N` pre-releases whose
  observation periods pass clean (§1).
- Version scheme is SemVer: current `0.x` line remains breaking-change
  at will, `1.0.0-rc.N` freezes the breaking-change surface, `1.0.0`
  commits to it (§2).
- Post-v1, breaking changes require a deprecation window of at least
  one minor release, with a release-notes call-out and a runtime warning
  when the deprecated path is hit (§3).
- v1.0 ships linuxX64 and macOS (`macosX64` / `macosArm64`) and
  linuxArm64. Windows is out of scope for v1.0 and has no tracked issue;
  a future ADR revisits after v1 lands (§4).
- Yank policy: a shipped tag may be marked yanked on the GitHub Release
  page, but the tarball stays reachable so existing installs do not
  brick. Replacement is a new patch release, never an in-place retag
  (§5).

## Context and Problem Statement

kolt has a `v1.0` milestone on GitHub that tracks the open issues which
must close before v1.0 ships. The CLAUDE.md project memory already
records "no backward compatibility until v1.0"; past that line, the
project takes on the usual stable-release obligations. Three questions
have been decided informally but are not written down:

1. **What is the gate between milestone-clear and the v1.0 tag?** Simply
   cutting `1.0.0` the moment the milestone empties leaves no
   observation window for regressions. Existing practice is to run an
   RC period first, but the duration, success criteria, and how RCs
   are published are not recorded.
2. **What does kolt commit to after v1.0?** SemVer is the obvious
   choice, but the deprecation window length, the yank policy, and
   whether patch releases can accept new `kolt.toml` schema fields
   need an explicit line.
3. **Which platforms does v1.0 support?** macOS and linuxArm64 have
   issues (#82, #83); Windows is mentioned as out-of-scope in #84 but
   nowhere authoritative. A reader looking for "does kolt 1.0 run on
   Windows?" cannot answer from the repo today.

ADR 0018 §4 covers release tarball packaging but is silent on when
tarballs turn from "0.x continuous" into "release-candidate" into
"stable".

## Decision Drivers

- **Concrete cut criteria.** A maintainer should be able to decide
  "now is or is not the time to tag v1.0" from this ADR without asking.
- **User visibility.** Whatever is committed to must be visible from
  the GitHub Release page or the README, not buried in ADRs only
  contributors read.
- **Observation period that reflects real use.** An RC whose only
  exercise is CI self-host-smoke is not a signal; real users must
  install it.
- **Reversibility.** A policy bug found post-v1 (e.g. yank mechanics)
  must be fixable without a v2.0 cut.

## Decision Outcome

Chosen: the five-bullet Summary above, because each bullet closes an
informal decision that currently lives only in conversation.

### §1 Gate to v1.0

- The `v1.0` GitHub milestone must reach zero open issues. Opening a new
  issue into the milestone after the gate re-opens it.
- After milestone-clear, the next release published is
  `1.0.0-rc.1`, not `1.0.0`. The RC tag fires the same
  `.github/workflows/release.yml` path as a stable tag (once #230 lands)
  and produces `kolt-1.0.0-rc.1-<os>-<arch>.tar.gz` on the GitHub
  Release page, marked as a pre-release.
- Observation period for each RC: minimum 14 days of real-user
  exposure with no bug report tagged `regression-v1`. A new RC (`-rc.2`,
  etc.) resets the window only for the fix surface; an uneventful RC
  that expires is promoted to `1.0.0` by re-tagging on the same commit
  (i.e. `1.0.0` and `1.0.0-rc.N` point at the same SHA).
- At least one RC is required. There is no upper bound; ship RCs until
  an observation window closes clean.

### §2 Version scheme

- Pre-v1: the `0.x.y` line. Breaking changes land at will, with
  release-note guidance per CLAUDE.md ("expect users to run
  `rm -rf ~/.kolt/daemon/`" and similar). No deprecation window is
  required in `0.x`.
- RC line: `1.0.0-rc.N`, `N` starts at 1 and increments per RC. RCs are
  always pre-releases on GitHub, never tagged `latest`.
- Stable: `1.0.0`, then `1.0.y` for bug-fix-only patches and `1.x.0`
  for additive features. `x` increments may relax compatibility where
  §3 permits.
- Pre-release tail is reserved for experiments (e.g. `1.1.0-beta.1`)
  and does not fire the release workflow's `latest` tag.

### §3 Post-v1 compatibility commitments

- **kolt.toml schema.** Additive fields are allowed in any minor; removed
  or semantically-changed fields require a deprecation window of at
  least one minor release, during which the old shape continues to work
  and a stderr warning names the replacement.
- **CLI surface.** Same rule as kolt.toml. Adding a new command or flag
  is minor; removing or re-meaning an existing command or flag is
  minor after a deprecation window, never patch.
- **On-disk layout** (`~/.kolt/`). Reorganisations require either a
  one-time migration performed on first run or a deprecation window;
  which path is taken is decided per-case in the ADR proposing the
  move.
- **Wire protocol.** The daemon protocol carries its own versioning
  (existing `protocolVersion` field in the frame header, ADR 0016).
  A protocol bump is allowed at a minor boundary if the native client
  negotiates down. A hard break requires a major.
- **Deprecation warnings** are emitted once per kolt invocation, gated
  behind `KOLT_HIDE_DEPRECATION_WARNINGS=1` for CI / scripted use.

### §4 Platform scope at v1.0

- Supported targets in the first v1.0 cut: `linuxX64`, `linuxArm64`,
  `macosX64`, `macosArm64`. These are the targets #82 and #83 unblock
  plus the existing `linuxX64`.
- Windows is **out of scope** for v1.0 and has no tracking issue. A
  future ADR revisits after v1 ships; the decision is not "never",
  it is "not required to call kolt stable on Linux and macOS".
- Target addition post-v1.0 is a minor-release event. Removing a
  supported target is a major.

### §5 Yank policy

- A shipped tag is never retagged on a different SHA. If a release is
  found broken after publication, the fix ships as a new patch and the
  bad release is marked yanked on its GitHub Release page with a
  pointer to the replacement.
- A yanked tarball stays reachable at its original URL so existing
  installs can still re-download. The yank is advisory, not
  destructive.
- `install.sh` (see ADR 0018 §4, #230) pins the latest non-yanked
  release by default. A user can explicitly install a yanked version
  with `KOLT_VERSION=<yanked>` and accepts the breakage.

### Consequences

**Positive**
- A reader can answer "what does kolt commit to post-v1?" from one
  document.
- The milestone acts as the single checklist; no parallel "v1 readiness"
  bookkeeping is required.
- Breaking changes post-v1 are visible to the user through a stderr
  warning before the removal release lands.

**Negative**
- 14-day RC observation windows delay v1.0 by at least two weeks past
  milestone-clear; no workaround.
- Windows users get no v1 story. The trade-off accepts the scope
  reduction to ship v1 on a predictable schedule.

### Confirmation

- The `v1.0` milestone on GitHub is the source of truth for the gate.
  A new `v1.0` label on issues is redundant and not created.
- Release workflow (#230) enforces SemVer tag format via a pre-publish
  regex; a tag not matching `^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-rc\.[1-9]\d*)?$`
  fails the job.
- RC observation period is tracked by maintainer memo, not automation.
  An RC that ships with an open `regression-v1` issue older than its
  cut time is by definition not clean and blocks the next RC or the
  v1.0 cut.

## Alternatives considered

1. **Cut `1.0.0` the moment the milestone empties.** Rejected. Even a
   perfect milestone cannot catch integration bugs that only surface
   under real install-and-use flows; the RC window is a cheap safety
   margin against "we forgot to test X on a fresh machine" regressions.
2. **Indefinite beta (no v1.0 promise).** Rejected. "Pre-v1" is
   already the status quo and the memory already records the cost
   ("no back-compat until v1.0"). Staying pre-v1 indefinitely would
   keep kolt unrecommendable for real projects, which is the entire
   point of cutting v1.0.
3. **CalVer (`YYYY.MM.N`).** Rejected. kolt's users consume its
   breakage budget, not its release cadence; SemVer communicates the
   breakage budget, CalVer does not.
4. **Drop the 14-day RC floor and ship as soon as an RC is "looks
   fine".** Rejected. Without an explicit floor the observation
   period collapses to whoever is most eager to ship; a hard minimum
   is the cheapest guard against this.

## Related

- #230 — `install.sh` + release workflow that RCs consume (ADR 0018 §4).
- #84 — multi-platform binary distribution, unblocks §4.
- ADR 0018 §4 — release tarball packaging (this ADR sits on top of it).
- CLAUDE.md — "no backward compatibility until v1.0" is the contract
  this ADR takes over once v1.0 ships.
