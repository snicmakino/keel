package kolt.build

import com.github.michaelbull.result.Result

// A CompilerBackend turns a CompileRequest into a compiled output. Two
// implementations are planned:
//   - SubprocessCompilerBackend: invokes kotlinc via fork+exec (legacy path).
//   - DaemonCompilerBackend: sends Message.Compile over a Unix domain socket
//     to a warm kolt-compiler-daemon process (#14 Phase A).
//
// The interface exists so doBuild() can swap backends without knowing which
// one runs the compiler, and so the fallback policy can live in a composition
// (FallbackCompilerBackend) rather than inside either backend.
interface CompilerBackend {
    fun compile(request: CompileRequest): Result<CompileOutcome, CompileError>
}

// Mirrors kolt.daemon.protocol.Message.Compile (JVM-side wire type) field-by-field.
// Keeping the shapes in sync lets DaemonCompilerBackend translate 1:1 without a
// lossy adapter. Future divergence should be absorbed inside DaemonCompilerBackend.
data class CompileRequest(
    val workingDir: String,
    val sources: List<String>,
    val classpath: List<String>,
    val outputPath: String,
    val moduleName: String,
    val extraArgs: List<String> = emptyList(),
)

data class CompileOutcome(
    val stdout: String,
    val stderr: String,
)

sealed interface CompileError {
    // Backend itself could not run or broke mid-compile. Fallback eligible.
    data class BackendUnavailable(val detail: String) : CompileError

    // Compiler ran but the user's Kotlin source did not compile. Not fallback
    // eligible — the subprocess path would fail identically.
    data class CompilationFailed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) : CompileError

    // kolt's own bug (e.g. empty argv, daemon exit code 64). Logged as an error
    // rather than a warning so self-inflicted problems do not disappear into the
    // fallback path silently.
    data class InternalMisuse(val detail: String) : CompileError
}

fun formatCompileError(error: CompileError, context: String): String = when (error) {
    is CompileError.CompilationFailed -> "error: $context failed with exit code ${error.exitCode}"
    is CompileError.BackendUnavailable -> "error: $context backend unavailable: ${error.detail}"
    is CompileError.InternalMisuse -> "error: $context internal bug: ${error.detail}"
}
