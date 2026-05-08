package kolt.infra.output

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val ESC = Char(0x1B).toString()

class DiagnosticWriterTest {

  @AfterTest
  fun resetGlobal() {
    ColorPolicy.install(ColorPolicy.Never)
  }

  // --- color disabled (Never) -----------------------------------------------

  @Test
  fun errorHeadlineOnlyPlain() {
    val lines =
      renderDiagnostic(RenderedDiagnostic(Severity.Error, "boom"), policy = ColorPolicy.Never)
    assertEquals(listOf("error: boom"), lines)
  }

  @Test
  fun warningHeadlineOnlyPlain() {
    val lines =
      renderDiagnostic(
        RenderedDiagnostic(Severity.Warning, "stale lockfile"),
        policy = ColorPolicy.Never,
      )
    assertEquals(listOf("warning: stale lockfile"), lines)
  }

  @Test
  fun noteHeadlineOnlyPlain() {
    val lines =
      renderDiagnostic(RenderedDiagnostic(Severity.Note, "hint here"), policy = ColorPolicy.Never)
    assertEquals(listOf("note: hint here"), lines)
  }

  @Test
  fun headlinePlusContextIndents() {
    val lines =
      renderDiagnostic(
        RenderedDiagnostic(
          severity = Severity.Error,
          headline = "parse failed",
          context = listOf("at /tmp/kolt.toml:5", "key: kotlin"),
        ),
        policy = ColorPolicy.Never,
      )
    assertEquals(listOf("error: parse failed", "  at /tmp/kolt.toml:5", "  key: kotlin"), lines)
  }

  @Test
  fun headlinePlusHintEmitsNoteLine() {
    val lines =
      renderDiagnostic(
        RenderedDiagnostic(
          severity = Severity.Error,
          headline = "lockfile out of date",
          hint = "Run `kolt update`",
        ),
        policy = ColorPolicy.Never,
      )
    assertEquals(listOf("error: lockfile out of date", "note: Run `kolt update`"), lines)
  }

  @Test
  fun headlinePlusContextPlusHint() {
    val lines =
      renderDiagnostic(
        RenderedDiagnostic(
          severity = Severity.Error,
          headline = "parse failed",
          context = listOf("at /a.toml:1"),
          hint = "Did you mean `kotlin`?",
        ),
        policy = ColorPolicy.Never,
      )
    assertEquals(
      listOf("error: parse failed", "  at /a.toml:1", "note: Did you mean `kotlin`?"),
      lines,
    )
  }

  // --- color enabled (Always) -----------------------------------------------

  @Test
  fun errorHeadlineWrappedWithRedAnsi() {
    val lines =
      renderDiagnostic(RenderedDiagnostic(Severity.Error, "boom"), policy = ColorPolicy.Always)
    assertEquals(listOf("$ESC[31merror:$ESC[0m boom"), lines)
  }

  @Test
  fun warningHeadlineWrappedWithYellowAnsi() {
    val lines =
      renderDiagnostic(
        RenderedDiagnostic(Severity.Warning, "be careful"),
        policy = ColorPolicy.Always,
      )
    assertEquals(listOf("$ESC[33mwarning:$ESC[0m be careful"), lines)
  }

  @Test
  fun noteHeadlineWrappedWithCyanAnsi() {
    val lines =
      renderDiagnostic(RenderedDiagnostic(Severity.Note, "fyi"), policy = ColorPolicy.Always)
    assertEquals(listOf("$ESC[36mnote:$ESC[0m fyi"), lines)
  }

  @Test
  fun hintLineUsesCyanNotePrefixWhenColorEnabled() {
    val lines =
      renderDiagnostic(
        RenderedDiagnostic(
          severity = Severity.Error,
          headline = "lockfile out of date",
          hint = "Run `kolt update`",
        ),
        policy = ColorPolicy.Always,
      )
    assertEquals(
      listOf(
        "$ESC[31merror:$ESC[0m lockfile out of date",
        "$ESC[36mnote:$ESC[0m Run `kolt update`",
      ),
      lines,
    )
  }

  @Test
  fun contextLinesAreNotColored() {
    val lines =
      renderDiagnostic(
        RenderedDiagnostic(severity = Severity.Error, headline = "h", context = listOf("c1", "c2")),
        policy = ColorPolicy.Always,
      )
    assertEquals(listOf("$ESC[31merror:$ESC[0m h", "  c1", "  c2"), lines)
  }

  @Test
  fun autoPolicyWritesToStderrAccordingToStderrTtyState() {
    val lines =
      renderDiagnostic(
        RenderedDiagnostic(Severity.Error, "h"),
        policy = ColorPolicy.Auto(isStderrTty = false, isStdoutTty = true),
      )
    assertEquals(listOf("error: h"), lines)
  }

  // --- public API uses default policy from ColorPolicy.current() ------------

  @Test
  fun defaultPolicyIsColorPolicyCurrent() {
    ColorPolicy.install(ColorPolicy.Always)
    val collected = mutableListOf<String>()
    eprintDiagnostic(RenderedDiagnostic(Severity.Error, "h"), sink = { collected += it })
    assertEquals(listOf("$ESC[31merror:$ESC[0m h"), collected)
  }

  @Test
  fun explicitPolicyOverridesCurrent() {
    ColorPolicy.install(ColorPolicy.Always)
    val collected = mutableListOf<String>()
    eprintDiagnostic(
      RenderedDiagnostic(Severity.Error, "h"),
      policy = ColorPolicy.Never,
      sink = { collected += it },
    )
    assertEquals(listOf("error: h"), collected)
  }

  // --- thin convenience wrappers --------------------------------------------

  @Test
  fun eprintErrorDelegatesToDiagnostic() {
    val collected = mutableListOf<String>()
    eprintError(
      "boom",
      context = listOf("at /a"),
      hint = "fix it",
      policy = ColorPolicy.Never,
      sink = { collected += it },
    )
    assertEquals(listOf("error: boom", "  at /a", "note: fix it"), collected)
  }

  @Test
  fun eprintWarningDelegatesToDiagnostic() {
    val collected = mutableListOf<String>()
    eprintWarning("careful", policy = ColorPolicy.Never, sink = { collected += it })
    assertEquals(listOf("warning: careful"), collected)
  }

  @Test
  fun eprintNoteDelegatesToDiagnostic() {
    val collected = mutableListOf<String>()
    eprintNote("fyi", policy = ColorPolicy.Never, sink = { collected += it })
    assertEquals(listOf("note: fyi"), collected)
  }
}
