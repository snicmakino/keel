package kolt.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FallbackReporterTest {

  private fun capture(err: CompileError): List<String> {
    val out = mutableListOf<String>()
    reportFallback(err) { out.add(it) }
    return out
  }

  @Test
  fun backendUnavailableOtherBecomesWarningWithDetail() {
    val messages = capture(CompileError.BackendUnavailable.Other("connect refused"))
    assertEquals(1, messages.size)
    assertTrue(messages.single().startsWith("warning: "))
    assertTrue(messages.single().contains("connect refused"))
  }

  @Test
  fun backendUnavailableForkFailedBecomesGenericWarning() {
    val messages = capture(CompileError.BackendUnavailable.ForkFailed)
    assertEquals(1, messages.size)
    assertTrue(messages.single().startsWith("warning: "))
  }

  @Test
  fun internalMisuseBecomesErrorLog() {
    val messages = capture(CompileError.InternalMisuse("sockaddr_un path too long"))
    assertEquals(1, messages.size)
    val line = messages.single()
    assertTrue(line.startsWith("error: "), "expected 'error: ' prefix, got: $line")
    assertTrue(line.contains("sockaddr_un path too long"))
  }

  @Test
  fun compilationFailedIsNotReported() {
    val messages =
      capture(
        CompileError.CompilationFailed(exitCode = 1, stdout = "", stderr = "user code broken")
      )
    assertEquals(emptyList(), messages)
  }

  @Test
  fun noCommandIsNotReported() {
    val messages = capture(CompileError.NoCommand)
    assertEquals(emptyList(), messages)
  }
}
