package keel.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VersionCheckTest {

    @Test
    fun parseStandardKotlincOutput() {
        val output = "info: kotlinc-jvm 2.1.0 (JRE 21.0.7+6-LTS)"
        assertEquals("2.1.0", parseKotlincVersion(output))
    }

    @Test
    fun parseDifferentVersion() {
        val output = "info: kotlinc-jvm 1.9.22 (JRE 17.0.1+12)"
        assertEquals("1.9.22", parseKotlincVersion(output))
    }

    @Test
    fun parseMultilineOutput() {
        val output = "info: kotlinc-jvm 2.1.0 (JRE 21.0.7+6-LTS)\nsome other line"
        assertEquals("2.1.0", parseKotlincVersion(output))
    }

    @Test
    fun unexpectedFormatReturnsNull() {
        assertNull(parseKotlincVersion("something unexpected"))
    }

    @Test
    fun emptyStringReturnsNull() {
        assertNull(parseKotlincVersion(""))
    }
}
