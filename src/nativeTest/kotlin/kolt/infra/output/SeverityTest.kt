package kolt.infra.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SeverityTest {
  @Test
  fun threeDistinctValues() {
    assertEquals(3, Severity.entries.size)
    assertNotEquals(Severity.Error, Severity.Warning)
    assertNotEquals(Severity.Warning, Severity.Note)
    assertNotEquals(Severity.Error, Severity.Note)
  }

  @Test
  fun namesArePinned() {
    assertEquals("Error", Severity.Error.name)
    assertEquals("Warning", Severity.Warning.name)
    assertEquals("Note", Severity.Note.name)
  }
}
