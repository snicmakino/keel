---
status: accepted
date: 2026-04-08
---

# ADR 0002: Execute subprocesses via `fork` + `execvp`; capture output via `popen`

## Summary

- `executeCommand` passes a pre-split argument vector to `execvp` directly — no shell involvement, no quoting hazards. (§1)
- `waitpid` exit-status decoding distinguishes normal exit, signal death, and wait failure as distinct `ProcessError` variants. `waitpid` is retried on `EINTR`. (§2)
- `executeAndCapture` uses `popen` for metadata commands where the output is the result. Callers must pass only literal, trusted command strings — never user input. (§3)
- The child calls `_exit(127)` after a failed `execvp` to suppress double-flush of parent stdio buffers; `127` matches the shell convention for "command not found". (§4)
- All POSIX call sites are annotated `@OptIn(ExperimentalForeignApi::class)` at function level. (§5)

## Context and Problem Statement

kolt shells out constantly: `kotlinc` for compilation, `java` for running jars and JUnit Platform, `ktfmt` for formatting, and archive tools during toolchain installation. Every call must (a) accept an argument vector without shell interpretation, (b) propagate the child's exit code verbatim, and (c) distinguish start failure from non-zero exit from signal death.

Kotlin/Native provides no `ProcessBuilder` equivalent. The POSIX options available on linuxX64 are `system(3)`, `popen(3)`, `posix_spawn(3)`, and `fork(2)` + `execvp(3)` + `waitpid(2)`. `system` and the default `popen` mode run through `/bin/sh -c`, which is a correctness hazard for kotlinc argument vectors (classpath strings with `:` characters, argument files with spaces and `$`) and a shell-injection vector for any field sourced from `kolt.toml` (project name, main class, plugin args).

## Decision Drivers

- Argument vectors must reach the child process unmodified, with no shell tokenisation.
- Shell injection from `kolt.toml` values must be structurally impossible on the main build path.
- Exit-code semantics: `NonZeroExit(exitCode)` carries the real compiler return code; signal deaths are a distinct variant, not collapsed into a non-zero exit.
- No extra process overhead (no shell wrapper around the real child).
- Compatible with kolt's single-threaded, no-IPC build pipeline on linuxX64.

## Decision Outcome

Chosen option: **`fork` + `execvp` + `waitpid` for the main path; `popen` for output-capture side path**, because this is the only combination that satisfies shell-injection safety, accurate exit-code decoding, and zero extra process overhead simultaneously.

### §1 `executeCommand` — shell-free main path

`executeCommand(args: List<String>)` in `infra/Process.kt` allocates a null-terminated `CPointerVar<ByteVar>` array from `args`, calls `execvp`, and never involves a shell. A project name of `foo; rm -rf /` is a literal string passed as an `argv` element. Return type is `Result<Int, ProcessError>`.

`ProcessError` variants: `EmptyArgs`, `ForkFailed`, `WaitFailed`, `NonZeroExit(exitCode)`, `SignalKilled`.

### §2 `waitpid` exit-status decoding

Exit status is decoded from the raw `status` integer:

```
(status and 0x7F) == 0  →  WIFEXITED; exit code = (status shr 8) and 0xFF  →  NonZeroExit or Ok
otherwise               →  SignalKilled
```

`waitpid` is called in a retry loop on `EINTR` so a delivered signal does not spuriously produce `WaitFailed`.

### §3 `executeAndCapture` — shell-based output capture

`executeAndCapture(command: String)` calls `popen(command, "r")` and reads the child's stdout into a `StringBuilder`. Used for short metadata queries — `kotlinc -version`, `java -version` — where the output is the result. Exit status is decoded from `pclose`'s return value using the same bit-pattern logic as §2.

Callers must pass only literal, trusted command strings. Passing any user-supplied value from `kolt.toml` into this function is a bug. This invariant is enforced by convention, not by types; see Confirmation.

### §4 `_exit(127)` on `execvp` failure

After a failed `execvp`, the child calls `_exit` (not `exit`) to avoid flushing the parent's atexit handlers and stdio buffers a second time. The exit code `127` matches the POSIX shell convention for "command not found" and is observable as `NonZeroExit(127)` in the parent.

### §5 `@OptIn(ExperimentalForeignApi::class)`

Every function that calls a POSIX API is annotated at the function level, per the project coding convention in `CLAUDE.md`.

### Consequences

**Positive**
- No shell injection: `executeCommand` arguments are `argv` elements, not parsed by `/bin/sh`.
- No quoting rules for callers: spaces, `:`, `$`, backslash in classpath strings are transparent.
- Accurate exit codes: `NonZeroExit(exitCode)` carries the real value kotlinc or java returned, propagated through `ExitCode.kt`.
- Signal deaths surface as `ProcessError.SignalKilled`, not conflated with non-zero exit.
- One subprocess per build command — no shell wrapper in the process tree.

**Negative**
- Manual cinterop plumbing: the `argv` array construction (null terminator, `cstr.ptr` lifetime) is a native bug if wrong, not a Kotlin exception.
- Linux-only: `fork`/`execvp`/`waitpid` and the status bit layout are POSIX. A Windows port requires a separate `CreateProcessW` implementation behind the same `Process.kt` API.
- No stdout/stderr capture on the main path: kotlinc output goes straight to the terminal. Adding capture requires `pipe` + `dup2` + a reader thread — non-trivial in a single-threaded Kotlin/Native binary.
- `executeAndCapture` shell-safety invariant is convention-enforced only (see §3).

### Confirmation

`executeCommand` vs `executeAndCapture` call-site selection is reviewed on each PR touching `infra/Process.kt` or adding new shell-out call sites. The rule — user-input-bearing arguments go only to `executeCommand` — is stated in §3 and enforced by review. The `_exit`/`exit` distinction and `argv` null-terminator are checked by code review; no automated gate exists for them.

## Alternatives considered

1. **`system(3)` for everything.** Rejected. Every argument passes through `/bin/sh -c`, which is a correctness hazard for kotlinc argument vectors and a shell-injection vector for any `kolt.toml` field interpolated into the command string.

2. **`posix_spawn(3)` everywhere.** Rejected as unnecessary overhead. It requires `posix_spawn_file_actions_t` setup and `posix_spawnattr_t` configuration that kolt does not need. `fork` + `execvp` is simpler and gives identical semantics for kolt's single-threaded, no-environment-munging, no-IPC use case.

3. **`popen` for everything including the build path.** Rejected. Shell-based, so it has the same injection and quoting problems as `system`. It also exposes only one of stdout or stdin (not both), and wraps the child in a shell that pollutes the exit-code chain.

4. **Thin C helper binary for the fork/exec dance.** Rejected. `cinterop` already provides the necessary primitives. A C helper binary adds a second native artifact to ship and solves nothing `cinterop` does not.

## Related

- `src/nativeMain/kotlin/kolt/infra/Process.kt` — implementation
- ADR 0001 — defines `Result<V, E>` and `ProcessError` as the error-handling surface
