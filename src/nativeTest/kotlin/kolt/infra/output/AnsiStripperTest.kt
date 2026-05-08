package kolt.infra.output

import kotlin.test.Test
import kotlin.test.assertEquals

private const val ESC = ""

class AnsiStripperTest {
  @Test
  fun stripsRedSequence() {
    assertEquals("foo", AnsiStripper.strip("$ESC[31mfoo$ESC[0m"))
  }

  @Test
  fun stripsMultipleSequencesInOneString() {
    assertEquals(
      "head body tail",
      AnsiStripper.strip("$ESC[31mhead$ESC[0m $ESC[33mbody$ESC[0m $ESC[36mtail$ESC[0m"),
    )
  }

  @Test
  fun leavesPlainTextUnchanged() {
    assertEquals("no escape here", AnsiStripper.strip("no escape here"))
  }

  @Test
  fun stripsParameterizedCsi() {
    assertEquals("emph", AnsiStripper.strip("$ESC[1;31memph$ESC[0m"))
  }

  @Test
  fun preservesNewlinesAndPunctuation() {
    assertEquals(
      "line1\nline2: value",
      AnsiStripper.strip("$ESC[31mline1$ESC[0m\n$ESC[33mline2:$ESC[0m value"),
    )
  }
}
