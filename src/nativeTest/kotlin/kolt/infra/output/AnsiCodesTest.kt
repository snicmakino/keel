package kolt.infra.output

import kotlin.test.Test
import kotlin.test.assertEquals

class AnsiCodesTest {
  @Test
  fun redIsCsi31m() {
    assertEquals("[31m", AnsiCodes.RED)
  }

  @Test
  fun yellowIsCsi33m() {
    assertEquals("[33m", AnsiCodes.YELLOW)
  }

  @Test
  fun cyanIsCsi36m() {
    assertEquals("[36m", AnsiCodes.CYAN)
  }

  @Test
  fun resetIsCsi0m() {
    assertEquals("[0m", AnsiCodes.RESET)
  }
}
