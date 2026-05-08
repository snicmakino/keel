package kolt.build

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.infra.ProcessError
import kolt.infra.executeCommand
import kolt.infra.output.ColorPolicy
import kolt.infra.output.Stream

// kotlinc is a shell wrapper that resolves java through `$JAVA_HOME` first;
// without it, the wrapper falls back to whatever `java` is on PATH and exits
// 127 when that lookup also fails. `javaHome` injects the env var into the
// child so the wrapper does not depend on the host's PATH.
//
// `colorPolicy` is a seam so tests drive both branches without re-installing
// the global ColorPolicy. Production callers leave it at the default and the
// CLI's startup `ColorPolicy.install` governs behavior.
class SubprocessCompilerBackend(
  private val kotlincBin: String,
  private val javaHome: String? = null,
  private val colorPolicy: () -> ColorPolicy = ColorPolicy::current,
) : CompilerBackend {

  override fun compile(request: CompileRequest): Result<CompileOutcome, CompileError> {
    val argv = subprocessArgv(kotlincBin, request)
    val env = buildMap {
      if (javaHome != null) put("JAVA_HOME", javaHome)
      // Signal kotlinc to emit plain (non-ANSI) diagnostics when kolt's color
      // is off. Pass-through is the inherit-default — we set nothing when
      // color is on so the child observes parent env verbatim.
      if (!colorPolicy().shouldColor(Stream.Stderr)) put("NO_COLOR", "1")
    }
    executeCommand(argv, env).getOrElse { err ->
      return Err(mapProcessErrorToCompileError(err))
    }
    return Ok(CompileOutcome(stdout = "", stderr = ""))
  }
}

internal fun subprocessArgv(kotlincBin: String, request: CompileRequest): List<String> = buildList {
  add(kotlincBin)
  if (request.classpath.isNotEmpty()) {
    add("-cp")
    add(request.classpath.joinToString(":"))
  }
  addAll(request.sources)
  addAll(request.extraArgs)
  // Forward moduleName so the subprocess path produces the same Kotlin module
  // identity as the daemon path; required for `internal` access from the test
  // source set in `testBuildCommand`.
  add("-module-name")
  add(request.moduleName)
  for (friendPath in request.friendPaths) {
    add("-Xfriend-paths=$friendPath")
  }
  add("-d")
  add(request.outputPath)
}

internal fun mapProcessErrorToCompileError(error: ProcessError): CompileError =
  when (error) {
    is ProcessError.EmptyArgs -> CompileError.NoCommand
    is ProcessError.ForkFailed -> CompileError.BackendUnavailable.ForkFailed
    is ProcessError.WaitFailed -> CompileError.BackendUnavailable.WaitFailed
    is ProcessError.PopenFailed -> CompileError.BackendUnavailable.PopenFailed
    is ProcessError.NonZeroExit ->
      CompileError.CompilationFailed(exitCode = error.exitCode, stdout = "", stderr = "")
    is ProcessError.SignalKilled -> CompileError.BackendUnavailable.SignalKilled
  }
