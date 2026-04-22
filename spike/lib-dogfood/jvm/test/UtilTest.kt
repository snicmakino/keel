package dogfood

import kotlin.test.Test
import kotlin.test.assertEquals

class UtilTest {
    @Test
    fun greet_returns_canonical_message() {
        assertEquals("hello from jvm lib", Util.greet())
    }
}
