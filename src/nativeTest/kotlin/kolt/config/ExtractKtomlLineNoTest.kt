package kolt.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ExtractKtomlLineNoTest {
  @Test
  fun extractsLineNumberFromKtomlPrefix() {
    assertEquals(5 to "foo", extractKtomlLineNo("Line 5: foo"))
  }

  @Test
  fun returnsNullAndOriginalWhenNoPrefix() {
    assertEquals(null to "foo", extractKtomlLineNo("foo"))
  }

  @Test
  fun returnsNullAndEmptyWhenMessageIsNull() {
    assertEquals(null to "", extractKtomlLineNo(null))
  }

  @Test
  fun nonNumericLinePrefixIsNotMatched() {
    // ktoml's `Line N:` prefix uses digits; a non-numeric variant must not match.
    assertEquals(null to "Line abc: foo", extractKtomlLineNo("Line abc: foo"))
  }

  @Test
  fun multiDigitLineNumber() {
    assertEquals(123 to "syntax error here", extractKtomlLineNo("Line 123: syntax error here"))
  }

  @Test
  fun emptyMessageReturnsNullPair() {
    assertEquals(null to "", extractKtomlLineNo(""))
  }

  @Test
  fun prefixWithoutSpaceDoesNotMatch() {
    // `Line 5:foo` (no space after colon) should not match — ktoml emits a space.
    assertEquals(null to "Line 5:foo", extractKtomlLineNo("Line 5:foo"))
  }
}
