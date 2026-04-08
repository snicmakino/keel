package keel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun versionStringContainsKeelAndVersion() {
        val result = versionString()
        assertEquals("keel $KEEL_VERSION", result)
    }

    @Test
    fun keelVersionIsValidSemver() {
        assertTrue(KEEL_VERSION.matches(Regex("""\d+\.\d+\.\d+""")))
    }
}
