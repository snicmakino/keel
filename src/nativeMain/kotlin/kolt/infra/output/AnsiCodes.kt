package kolt.infra.output

// ANSI CSI escape sequences for terminal coloring. Encoded with explicit
// `` so the source remains correct regardless of editor / tooling
// handling of the raw ESC byte.
object AnsiCodes {
  const val RED: String = "[31m"
  const val YELLOW: String = "[33m"
  const val CYAN: String = "[36m"
  const val RESET: String = "[0m"
}
