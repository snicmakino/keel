package kolt.cli

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
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
import kotlin.test.assertNotNull

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

    private fun createTempDir(prefix: String): String {
        val template = "/tmp/${prefix}XXXXXX"
        val buf = template.encodeToByteArray().copyOf(template.length + 1)
        buf.usePinned { pinned ->
            val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
            return result.toKString()
        }
    }
}
