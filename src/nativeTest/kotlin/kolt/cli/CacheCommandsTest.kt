package kolt.cli

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.directorySize
import kolt.infra.fileExists
import kolt.infra.formatBytes
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.mkdtemp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class CacheCommandsTest {
    private var tmpDir: String = ""

    @AfterTest
    fun tearDown() {
        if (tmpDir.isNotEmpty() && fileExists(tmpDir)) {
            removeDirectoryRecursive(tmpDir)
        }
    }

    @Test
    fun parseArgsWithoutFlagsIsCacheOnly() {
        val parsed = parseCacheCleanArgs(emptyList()).getOrElse { error("unexpected: $it") }
        assertEquals(false, parsed.includeTools)
    }

    @Test
    fun parseArgsWithToolsFlagIncludesTools() {
        val parsed = parseCacheCleanArgs(listOf("--tools")).getOrElse { error("unexpected: $it") }
        assertEquals(true, parsed.includeTools)
    }

    @Test
    fun parseArgsRejectsUnknownFlag() {
        assertNotNull(parseCacheCleanArgs(listOf("--unknown")).getError())
    }

    @Test
    fun formatBytesFormatsUnits() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun formatBytesRoundsHalfUp() {
        // 1599 B / 1024 = 1.5615... → rounds to 1.6 KB, not truncates to 1.5
        assertEquals("1.6 KB", formatBytes(1599))
        // 1075 B / 1024 = 1.0498... → rounds to 1.0 KB
        assertEquals("1.0 KB", formatBytes(1075))
    }

    @Test
    fun directorySizeSumsRecursively() {
        tmpDir = createTempDir("kolt-cache-size-")
        ensureDirectoryRecursive("$tmpDir/nested").getOrElse { error("mkdir failed") }
        writeFileAsString("$tmpDir/a.txt", "0123456789").getOrElse { error("write failed") }
        writeFileAsString("$tmpDir/nested/b.txt", "abcde").getOrElse { error("write failed") }

        assertEquals(15L, directorySize(tmpDir))
    }

    @Test
    fun directorySizeReturnsZeroForMissingPath() {
        assertEquals(0L, directorySize("/tmp/nonexistent-kolt-dir-xyz"))
    }

    @Test
    fun cleanCacheDirsRemovesCacheAndReportsFreedBytes() {
        tmpDir = createTempDir("kolt-clean-cache-")
        val paths = KoltPaths(home = tmpDir)
        ensureDirectoryRecursive(paths.cacheBase).getOrElse { error("mkdir cache failed") }
        writeFileAsString("${paths.cacheBase}/a.jar", "0123456789").getOrElse { error("seed a.jar failed") }

        val summary = cleanCacheDirs(paths, includeTools = false).getOrElse { error("clean failed: $it") }

        assertEquals(10L, summary.freedBytes)
        assertEquals(listOf(paths.cacheBase), summary.removedPaths)
        assertFalse(fileExists(paths.cacheBase))
    }

    @Test
    fun cleanCacheDirsReportsNothingWhenCacheMissing() {
        tmpDir = createTempDir("kolt-clean-nothing-")
        val paths = KoltPaths(home = tmpDir)

        val summary = cleanCacheDirs(paths, includeTools = false).getOrElse { error("clean failed: $it") }

        assertEquals(0L, summary.freedBytes)
        assertTrue(summary.removedPaths.isEmpty())
    }

    @Test
    fun cleanCacheDirsIncludesToolsWhenFlagSet() {
        tmpDir = createTempDir("kolt-clean-tools-")
        val paths = KoltPaths(home = tmpDir)
        ensureDirectoryRecursive(paths.cacheBase).getOrElse { error("mkdir cache failed") }
        ensureDirectoryRecursive(paths.toolsDir).getOrElse { error("mkdir tools failed") }
        writeFileAsString("${paths.cacheBase}/a.jar", "12345").getOrElse { error("seed a.jar failed") }
        writeFileAsString("${paths.toolsDir}/ktfmt.jar", "67890").getOrElse { error("seed ktfmt failed") }

        val summary = cleanCacheDirs(paths, includeTools = true).getOrElse { error("clean failed: $it") }

        assertEquals(10L, summary.freedBytes)
        assertEquals(2, summary.removedPaths.size)
        assertFalse(fileExists(paths.cacheBase))
        assertFalse(fileExists(paths.toolsDir))
    }

    @Test
    fun cleanCacheDirsLeavesToolsWhenFlagOff() {
        tmpDir = createTempDir("kolt-clean-leave-tools-")
        val paths = KoltPaths(home = tmpDir)
        ensureDirectoryRecursive(paths.cacheBase).getOrElse { error("mkdir cache failed") }
        ensureDirectoryRecursive(paths.toolsDir).getOrElse { error("mkdir tools failed") }
        writeFileAsString("${paths.toolsDir}/ktfmt.jar", "67890").getOrElse { error("seed ktfmt failed") }

        cleanCacheDirs(paths, includeTools = false).getOrElse { error("clean failed: $it") }

        assertTrue(fileExists(paths.toolsDir), "tools dir must survive when --tools is not set")
    }

    private fun createTempDir(prefix: String): String {
        val template = "/tmp/${prefix}XXXXXX"
        val buf = template.encodeToByteArray().copyOf(template.length + 1)
        buf.usePinned { pinned ->
            val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
            return result.toKString()
        }
    }
}
