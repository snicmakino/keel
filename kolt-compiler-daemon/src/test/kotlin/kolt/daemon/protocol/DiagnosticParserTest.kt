package kolt.daemon.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Pure-function parser pinned by unit tests. BTA's KotlinLogger surfaces
// per-source-position diagnostics as `path:line:col: severity: message`
// strings; this parser splits that shape into a structured Diagnostic so
// the native client can render IDE-style error lists. Lines that do not
// match stay as free-text error messages (the parser returns null and the
// caller keeps the original string).
class DiagnosticParserTest {

    @Test
    fun parsesErrorLineWithAbsolutePath() {
        val parsed = DiagnosticParser.parseLine(
            "/tmp/kolt/Main.kt:12:7: error: unresolved reference: foo",
        )
        assertEquals(
            Diagnostic(
                severity = Severity.Error,
                file = "/tmp/kolt/Main.kt",
                line = 12,
                column = 7,
                message = "unresolved reference: foo",
            ),
            parsed,
        )
    }

    @Test
    fun parsesWarningLine() {
        val parsed = DiagnosticParser.parseLine(
            "/src/a/B.kt:3:1: warning: parameter 'x' is never used",
        )
        assertEquals(Severity.Warning, parsed?.severity)
        assertEquals("parameter 'x' is never used", parsed?.message)
    }

    @Test
    fun parsesInfoLine() {
        val parsed = DiagnosticParser.parseLine("/p/Q.kt:1:1: info: note text")
        assertEquals(Severity.Info, parsed?.severity)
    }

    @Test
    fun returnsNullForNonDiagnosticLine() {
        assertNull(DiagnosticParser.parseLine("just some random kotlinc chatter"))
    }

    @Test
    fun returnsNullForMissingSeverity() {
        assertNull(DiagnosticParser.parseLine("/x/Y.kt:1:1: nope: whatever"))
    }

    @Test
    fun parseMessagesSplitsParsedAndUnparsedLines() {
        val input = listOf(
            "/tmp/A.kt:5:3: error: bad",
            "something else",
            "/tmp/B.kt:1:1: warning: stylistic",
        )
        val (diagnostics, plain) = DiagnosticParser.parseMessages(input)
        assertEquals(
            listOf(
                Diagnostic(Severity.Error, "/tmp/A.kt", 5, 3, "bad"),
                Diagnostic(Severity.Warning, "/tmp/B.kt", 1, 1, "stylistic"),
            ),
            diagnostics,
        )
        assertEquals(listOf("something else"), plain)
    }

    @Test
    fun parsesLineWithExtraSpacesAroundSeverity() {
        val parsed = DiagnosticParser.parseLine(
            "/a/B.kt:2:4:   error:   msg with padding",
        )
        assertEquals("msg with padding", parsed?.message)
    }

    @Test
    fun trailingStackFrameLinesStayUnparsed() {
        // CapturingKotlinLogger appends `\n\tat Foo.bar(...)` for throwables.
        // That trailing frame must not masquerade as a diagnostic.
        assertNull(DiagnosticParser.parseLine("\tat foo.Bar.baz(Bar.kt:42)"))
    }
}
