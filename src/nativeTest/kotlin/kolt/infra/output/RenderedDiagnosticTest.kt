package kolt.infra.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RenderedDiagnosticTest {
  @Test
  fun headlineOnlyHasEmptyContextAndNullHint() {
    val diag = RenderedDiagnostic(Severity.Error, "boom")
    assertEquals(Severity.Error, diag.severity)
    assertEquals("boom", diag.headline)
    assertEquals(emptyList(), diag.context)
    assertNull(diag.hint)
  }

  @Test
  fun fullShape() {
    val diag =
      RenderedDiagnostic(
        severity = Severity.Warning,
        headline = "stale lockfile",
        context = listOf("at /tmp/kolt.lock", "key: dependencies"),
        hint = "Run `kolt update`",
      )
    assertEquals("stale lockfile", diag.headline)
    assertEquals(listOf("at /tmp/kolt.lock", "key: dependencies"), diag.context)
    assertEquals("Run `kolt update`", diag.hint)
  }

  @Test
  fun equalityIsValueBased() {
    val a = RenderedDiagnostic(Severity.Note, "hi", listOf("x"), "y")
    val b = RenderedDiagnostic(Severity.Note, "hi", listOf("x"), "y")
    val c = RenderedDiagnostic(Severity.Note, "hi", listOf("x"), null)
    assertEquals(a, b)
    assertNotEquals(a, c)
  }

  @Test
  fun copyKeepsUnspecifiedFields() {
    val original = RenderedDiagnostic(Severity.Error, "first", listOf("a"), null)
    val updated = original.copy(headline = "second")
    assertEquals("second", updated.headline)
    assertEquals(Severity.Error, updated.severity)
    assertEquals(listOf("a"), updated.context)
    assertNull(updated.hint)
  }
}
