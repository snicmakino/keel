package keel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionCompareTest {

    @Test
    fun equalVersionsReturnZero() {
        assertEquals(0, compareVersions("1.0.0", "1.0.0"))
    }

    @Test
    fun higherMajorVersionIsGreater() {
        assertTrue(compareVersions("2.0.0", "1.0.0") > 0)
    }

    @Test
    fun lowerMajorVersionIsLess() {
        assertTrue(compareVersions("1.0.0", "2.0.0") < 0)
    }

    @Test
    fun higherMinorVersionIsGreater() {
        assertTrue(compareVersions("1.2.0", "1.1.0") > 0)
    }

    @Test
    fun higherPatchVersionIsGreater() {
        assertTrue(compareVersions("1.0.2", "1.0.1") > 0)
    }

    @Test
    fun numericComparisonNotLexicographic() {
        assertTrue(compareVersions("1.10.0", "1.9.0") > 0)
    }

    @Test
    fun differentSegmentCountsShorterIsSmallerWhenPrefixEqual() {
        assertTrue(compareVersions("1.0.0.1", "1.0.0") > 0)
    }

    @Test
    fun snapshotIsLessThanRelease() {
        assertTrue(compareVersions("1.0.0-SNAPSHOT", "1.0.0") < 0)
    }

    @Test
    fun alphaIsLessThanBeta() {
        assertTrue(compareVersions("1.0.0-alpha", "1.0.0-beta") < 0)
    }

    @Test
    fun betaIsLessThanRc() {
        assertTrue(compareVersions("1.0.0-beta", "1.0.0-rc") < 0)
    }

    @Test
    fun rcIsLessThanRelease() {
        assertTrue(compareVersions("1.0.0-rc", "1.0.0") < 0)
    }

    @Test
    fun snapshotIsLessThanAlpha() {
        assertTrue(compareVersions("1.0.0-SNAPSHOT", "1.0.0-alpha") < 0)
    }

    @Test
    fun qualifierComparisonIsCaseInsensitive() {
        assertTrue(compareVersions("1.0.0-Alpha", "1.0.0-BETA") < 0)
    }

    @Test
    fun numericQualifierComparedNumerically() {
        assertTrue(compareVersions("1.0.0-alpha2", "1.0.0-alpha10") < 0)
    }

    @Test
    fun twoSegmentVersion() {
        assertTrue(compareVersions("1.1", "1.0") > 0)
    }

    @Test
    fun singleSegmentVersion() {
        assertTrue(compareVersions("2", "1") > 0)
    }

    @Test
    fun trailingZeroSegmentsAreEqual() {
        assertEquals(0, compareVersions("1.0", "1.0.0"))
    }

    @Test
    fun trailingZeroSegmentsAreEqualLonger() {
        assertEquals(0, compareVersions("1.0.0", "1.0.0.0"))
    }
}
