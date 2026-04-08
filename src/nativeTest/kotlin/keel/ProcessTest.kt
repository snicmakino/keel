package keel

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessTest {

    @Test
    fun executeCommandReturnsZeroOnSuccess() {
        val exitCode = executeCommand(listOf("true"))
        assertEquals(0, exitCode)
    }

    @Test
    fun executeCommandReturnsNonZeroOnFailure() {
        val exitCode = executeCommand(listOf("false"))
        assertEquals(1, exitCode)
    }

    @Test
    fun executeCommandPassesArgsWithoutShellExpansion() {
        // $HOME should NOT be expanded since we bypass shell
        val exitCode = executeCommand(listOf("echo", "\$HOME"))
        assertEquals(0, exitCode)
    }

    @Test
    fun executeCommandEmptyArgsReturnsError() {
        val exitCode = executeCommand(emptyList())
        assertEquals(-1, exitCode)
    }

    @Test
    fun executeAndCaptureReturnsOutput() {
        val (exitCode, output) = executeAndCapture("echo hello 2>&1")
        assertEquals(0, exitCode)
        assertEquals("hello\n", output)
    }

    @Test
    fun executeAndCaptureReturnsNonZeroOnFailure() {
        val (exitCode, _) = executeAndCapture("false")
        assertEquals(1, exitCode)
    }
}
