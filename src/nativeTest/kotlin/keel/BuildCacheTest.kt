package keel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildCacheTest {

    @Test
    fun upToDateWhenAllMtimesMatch() {
        val state = BuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            outputMtime = 3000L,
            lockfileMtime = 500L
        )
        assertTrue(isBuildUpToDate(current = state, cached = state))
    }

    @Test
    fun notUpToDateWhenCachedIsNull() {
        val state = BuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            outputMtime = 3000L,
            lockfileMtime = null
        )
        assertFalse(isBuildUpToDate(current = state, cached = null))
    }

    @Test
    fun notUpToDateWhenConfigMtimeChanged() {
        val cached = BuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            outputMtime = 3000L,
            lockfileMtime = null
        )
        val current = cached.copy(configMtime = 1500L)
        assertFalse(isBuildUpToDate(current = current, cached = cached))
    }

    @Test
    fun notUpToDateWhenSourceMtimeChanged() {
        val cached = BuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            outputMtime = 3000L,
            lockfileMtime = null
        )
        val current = cached.copy(sourcesNewestMtime = 2500L)
        assertFalse(isBuildUpToDate(current = current, cached = cached))
    }

    @Test
    fun notUpToDateWhenOutputMtimeChanged() {
        val cached = BuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            outputMtime = 3000L,
            lockfileMtime = null
        )
        val current = cached.copy(outputMtime = 3500L)
        assertFalse(isBuildUpToDate(current = current, cached = cached))
    }

    @Test
    fun notUpToDateWhenOutputMtimeIsNull() {
        val cached = BuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            outputMtime = 3000L,
            lockfileMtime = null
        )
        val current = cached.copy(outputMtime = null)
        assertFalse(isBuildUpToDate(current = current, cached = cached))
    }

    @Test
    fun notUpToDateWhenLockfileMtimeChanged() {
        val cached = BuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            outputMtime = 3000L,
            lockfileMtime = 500L
        )
        val current = cached.copy(lockfileMtime = 600L)
        assertFalse(isBuildUpToDate(current = current, cached = cached))
    }

    @Test
    fun upToDateWhenBothLockfileMtimesNull() {
        val state = BuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            outputMtime = 3000L,
            lockfileMtime = null
        )
        assertTrue(isBuildUpToDate(current = state, cached = state))
    }

    @Test
    fun serializeAndDeserializeRoundTrip() {
        val state = BuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            outputMtime = 3000L,
            lockfileMtime = 500L,
            classpath = "/cache/a.jar:/cache/b.jar"
        )
        val json = serializeBuildState(state)
        val parsed = parseBuildState(json)
        assertEquals(state, parsed)
    }

    @Test
    fun serializeWithNullLockfileMtime() {
        val state = BuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            outputMtime = 3000L,
            lockfileMtime = null
        )
        val json = serializeBuildState(state)
        val parsed = parseBuildState(json)
        assertEquals(state, parsed)
        assertNull(parsed!!.classpath)
    }

    @Test
    fun classpathPreservedInBuildState() {
        val state = BuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            outputMtime = 3000L,
            lockfileMtime = 500L,
            classpath = "/cache/okhttp-jvm-5.3.2.jar:/cache/okio-jvm-3.16.4.jar"
        )
        val json = serializeBuildState(state)
        val parsed = parseBuildState(json)
        assertEquals("/cache/okhttp-jvm-5.3.2.jar:/cache/okio-jvm-3.16.4.jar", parsed!!.classpath)
    }

    @Test
    fun parseInvalidJsonReturnsNull() {
        assertNull(parseBuildState("not json"))
    }

    @Test
    fun parseEmptyStringReturnsNull() {
        assertNull(parseBuildState(""))
    }

}
