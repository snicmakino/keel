package kolt.daemon.ic

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.mapBoth
import java.nio.file.Files
import java.nio.file.Path

// Self-heal wrapper defined by ADR 0019 §7. On an `IcError.InternalError`
// from the wrapped adapter it (a) deletes the per-project `workingDir`
// tree — the presumed-corrupt IC state — and (b) invokes the delegate
// once more with the same request. BTA's `SourcesChanges.ToBeCalculated`
// mode walking an empty state dir naturally degrades to a full recompile,
// so no "force cold" flag is needed on `IcRequest`.
//
// Placement rationale: the ADR specifies self-heal lives "inside the
// adapter". This wrapper is still inside the `:ic` module — daemon core
// imports `IncrementalCompiler` without knowing whether it received a
// bare `BtaIncrementalCompiler` or the wrapped variant. The split into
// two classes is an implementation choice that keeps the retry logic
// unit-testable against a fake delegate (`SelfHealingIncrementalCompilerTest`),
// without any real BTA dependency, while honouring the ADR's boundary.
//
// Failure precedence: if the retry also fails, the retry's `IcError` is
// surfaced verbatim — a `CompilationFailed` from the retry wins over the
// original `InternalError`, and a second `InternalError` overwrites the
// first. This matches ADR §7: the user must see a useful compile message
// whenever the retry produced one, and must never see "incremental cache
// was corrupt".
//
// `VirtualMachineError` from the delegate is not absorbed. ADR 0019 §7
// (widened in this PR) routes JVM-fatal errors past the adapter so they
// reach `FallbackCompilerBackend` (ADR 0016 §5); catching them here and
// firing a retry would allocate more objects and reproduce the OOM in a
// loop.
class SelfHealingIncrementalCompiler(
    private val delegate: IncrementalCompiler,
    // Wipe action is injected so tests can observe the call without
    // touching a real filesystem, and so production can replace the
    // default recursive-delete with a different strategy (e.g. move-
    // to-quarantine-dir) without reopening this class. The default
    // below is what the ADR specifies: a recursive delete of the
    // per-project IC state tree.
    //
    // Signature note: returns the list of paths that the wipe tried
    // and failed to delete (empty on complete success). A partial wipe
    // on Linux is exceedingly rare (the ic state is under the daemon's
    // home directory, sole writer per ADR §5) but still observable
    // through the `ic.self_heal_wipe_failed` metric so a follow-up
    // doctor can distinguish "retry produced a new failure" from
    // "retry fired against a stale state dir because wipe leaked".
    private val wipe: (Path) -> List<Path> = ::defaultWipe,
    // ADR 0019 §7 "observability via metrics, not log spam": self-heal
    // events are recorded here so `kolt doctor` / smoke tests can see
    // what fired. Defaults to no-op so unit tests that do not care
    // about metrics stay terse.
    private val metrics: IcMetricsSink = NoopIcMetricsSink,
    // ADR 0019 §7 also requires a single stderr warning on self-heal
    // so a dogfooding user notices the event without having to grep
    // the structured metric stream. Injected so tests can observe.
    private val stderrWarn: (String) -> Unit = ::defaultStderrWarn,
) : IncrementalCompiler {

    override fun compile(request: IcRequest): Result<IcResponse, IcError> {
        val first = delegate.compile(request)
        return first.mapBoth(
            success = { first },
            failure = { error ->
                if (error is IcError.InternalError) {
                    metrics.record(METRIC_SELF_HEAL)
                    stderrWarn(
                        "kolt-compiler-daemon: self-heal fired for project working dir " +
                            "${request.workingDir}: ${error.cause.message ?: error.cause.javaClass.name}",
                    )
                    // Defensive wrap: `Files.walk` (used by `defaultWipe`)
                    // can raise `UncheckedIOException` or `SecurityException`
                    // if the working directory becomes unreadable mid-request.
                    // The workingDir is daemon-owned so this is a cold path,
                    // but `compile()` promises daemon core a `Result` — a
                    // thrown exception here would violate that contract and
                    // land on daemon core as a propagated stack trace instead
                    // of a metric + retry. Treat any throw as "full wipe
                    // failure"; the retry still runs against whatever state
                    // remains, consistent with the partial-wipe policy in
                    // ADR §7.
                    val wipeLeftovers = try {
                        wipe(request.workingDir)
                    } catch (t: Throwable) {
                        if (t is VirtualMachineError) throw t
                        listOf(request.workingDir)
                    }
                    if (wipeLeftovers.isNotEmpty()) {
                        // Partial wipe: at least one entry could not be
                        // deleted. The retry still runs — BTA's own
                        // `SourcesChanges.ToBeCalculated` mode will
                        // reconstruct what it can from the leftover
                        // state — but the metric lets a future doctor
                        // distinguish "retry produced a new error" from
                        // "retry fired against a stale state dir
                        // because wipe leaked on us". We deliberately
                        // do not abort the retry: a partial wipe still
                        // beats no wipe, and ADR §7 requires that a
                        // self-heal cycle always returns *some* result
                        // to daemon core without escalating to the
                        // subprocess fallback.
                        metrics.record(METRIC_SELF_HEAL_WIPE_FAILED, wipeLeftovers.size.toLong())
                    }
                    delegate.compile(request)
                } else {
                    first
                }
            },
        )
    }

    companion object {
        internal const val METRIC_SELF_HEAL: String = "ic.self_heal"
        internal const val METRIC_SELF_HEAL_WIPE_FAILED: String = "ic.self_heal_wipe_failed"

        // Recursively delete the working-dir tree. `Files.walk` in post-order
        // lets us delete children before their parent, which is the only
        // order `Files.delete` accepts for a non-empty directory. Returns
        // the list of paths that could not be deleted — expected to be
        // empty in the common case; non-empty means a file is held open
        // by something else on the JVM or the filesystem is returning
        // EBUSY. Either way the retry still runs against whatever state
        // remains.
        private fun defaultWipe(workingDir: Path): List<Path> {
            if (!Files.exists(workingDir)) return emptyList()
            val leftovers = mutableListOf<Path>()
            Files.walk(workingDir).use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .forEach { path ->
                        runCatching { Files.delete(path) }
                            .onFailure { leftovers.add(path) }
                    }
            }
            return leftovers
        }

        private fun defaultStderrWarn(message: String) {
            System.err.println(message)
        }
    }
}
