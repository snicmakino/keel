package kolt.infra.output

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.STDERR_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.getenv
import platform.posix.isatty

enum class Stream {
  Stderr,
  Stdout,
}

sealed class ColorPolicy {
  abstract fun shouldColor(stream: Stream): Boolean

  data object Always : ColorPolicy() {
    override fun shouldColor(stream: Stream): Boolean = true
  }

  data object Never : ColorPolicy() {
    override fun shouldColor(stream: Stream): Boolean = false
  }

  data class Auto(val isStderrTty: Boolean, val isStdoutTty: Boolean) : ColorPolicy() {
    override fun shouldColor(stream: Stream): Boolean =
      when (stream) {
        Stream.Stderr -> isStderrTty
        Stream.Stdout -> isStdoutTty
      }
  }

  companion object {
    // Mutated exactly once, at CLI startup, via `install`. Tests override the
    // active policy by passing an explicit `policy` argument to writer
    // functions (see DiagnosticWriter), not by re-installing — this keeps the
    // global state contract single-write-at-startup and prevents test leakage
    // across the unit suite.
    private var currentPolicy: ColorPolicy = Never

    fun fromEnv(noColorFlag: Boolean): ColorPolicy =
      resolveColorPolicy(noColorFlag = noColorFlag, getEnv = ::posixGetEnv, isTty = ::posixIsTty)

    fun install(policy: ColorPolicy) {
      currentPolicy = policy
    }

    fun current(): ColorPolicy = currentPolicy
  }
}

// `getEnv` / `isTty` are seam parameters so unit tests can drive every branch
// without touching the real process environment or controlling terminals.
internal fun resolveColorPolicy(
  noColorFlag: Boolean,
  getEnv: (String) -> String?,
  isTty: (Int) -> Boolean,
): ColorPolicy {
  if (noColorFlag) return ColorPolicy.Never
  // no-color.org: any non-empty value disables color. Empty string does not.
  val noColor = getEnv("NO_COLOR")
  if (!noColor.isNullOrEmpty()) return ColorPolicy.Never
  return ColorPolicy.Auto(isStderrTty = isTty(STDERR_FILENO), isStdoutTty = isTty(STDOUT_FILENO))
}

@OptIn(ExperimentalForeignApi::class)
private fun posixGetEnv(name: String): String? = getenv(name)?.toKString()

@OptIn(ExperimentalForeignApi::class) private fun posixIsTty(fd: Int): Boolean = isatty(fd) != 0
