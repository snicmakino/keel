package kolt.build

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError

/**
 * Composes two [CompilerBackend]s so that a failure from [primary]
 * which the policy classifies as recoverable is transparently retried
 * against [fallback]. This is the seam that lets `kolt build` default
 * to the warm JVM daemon (primary) while still honouring the ADR 0016
 * invariant that "the daemon is never load-bearing for correctness":
 * every daemon-level failure surfaces as a [CompileError] variant that
 * [isFallbackEligible] maps to `true`, and the subprocess path runs in
 * its place.
 *
 * Failures that represent real user-code problems
 * ([CompileError.CompilationFailed]) or kolt wiring mistakes that
 * cannot be papered over ([CompileError.NoCommand]) are returned as-is
 * — retrying would produce the same result, and hiding them behind a
 * silent fallback would be user-hostile.
 *
 * [onFallback] is a per-fallback observer hook. It is invoked exactly
 * once per compile, only on the path where the fallback actually
 * runs, and receives the *primary* error so the caller can decide how
 * loudly to surface it (warning for [CompileError.BackendUnavailable],
 * error log for [CompileError.InternalMisuse] — see ADR 0016 §3). It
 * defaults to a no-op so tests can construct instances without
 * plumbing logging infrastructure; production wiring (S7) supplies a
 * real reporter via `doBuild()`.
 */
class FallbackCompilerBackend(
    private val primary: CompilerBackend,
    private val fallback: CompilerBackend,
    private val onFallback: (CompileError) -> Unit = {},
) : CompilerBackend {

    override fun compile(request: CompileRequest): Result<CompileOutcome, CompileError> {
        val primaryResult = primary.compile(request)
        val primaryError = primaryResult.getError() ?: return primaryResult
        if (!isFallbackEligible(primaryError)) return Err(primaryError)
        onFallback(primaryError)
        return fallback.compile(request)
    }
}

/**
 * Classifies a [CompileError] by whether retrying the compile against
 * a different backend could plausibly succeed.
 *
 * - [CompileError.BackendUnavailable] — the backend itself failed to
 *   run the compiler (fork failed, daemon unreachable, protocol
 *   error). The subprocess path has a real chance of succeeding.
 * - [CompileError.InternalMisuse] — kolt produced a bad argument for
 *   the backend (e.g. a path too long for `sockaddr_un`). Fallback is
 *   still worthwhile because the subprocess path takes different
 *   arguments, but the caller should log loudly: ADR 0016 §3 treats
 *   exit 64 / internal misuse as a kolt bug that must not be silently
 *   swallowed.
 * - [CompileError.CompilationFailed] — kotlinc ran and the user's
 *   Kotlin source did not compile. A subprocess kotlinc would report
 *   the same failure.
 * - [CompileError.NoCommand] — an empty argv reached the backend.
 *   This is a kolt wiring mistake with no user-visible workaround; a
 *   subprocess retry would hit the same guard.
 */
fun isFallbackEligible(error: CompileError): Boolean = when (error) {
    is CompileError.BackendUnavailable -> true
    is CompileError.InternalMisuse -> true
    is CompileError.CompilationFailed -> false
    is CompileError.NoCommand -> false
}
