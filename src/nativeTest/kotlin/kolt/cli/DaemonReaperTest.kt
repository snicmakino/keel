@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.ensureDirectory
import kolt.infra.fileExists
import kolt.infra.writeFileAsString
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.mkdtemp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DaemonReaperTest {

    @Test
    fun emptyDaemonDirReturnsZeroReaped() {
        val dir = createTempDir("reaper-empty-")
        val result = reapStaleDaemons(dir)
        assertEquals(0, result.reaped)
        assertEquals(0, result.alive)
    }

    @Test
    fun directoryWithoutSocketIsReaped() {
        val base = createTempDir("reaper-nosock-")
        val projectDir = "$base/abc123"
        ensureDirectory(projectDir).getOrElse { error("mkdir failed") }
        writeFileAsString("$projectDir/daemon.log", "some log").getOrElse { error("write failed") }

        val result = reapStaleDaemons(base)
        assertEquals(1, result.reaped)
        assertFalse(fileExists(projectDir))
    }

    @Test
    fun directoryWithOrphanedSocketIsReaped() {
        val base = createTempDir("reaper-orphan-")
        val projectDir = "$base/def456"
        ensureDirectory(projectDir).getOrElse { error("mkdir failed") }
        // A regular file named daemon.sock — connect will fail (not a real socket)
        writeFileAsString("$projectDir/daemon.sock", "").getOrElse { error("write failed") }

        val result = reapStaleDaemons(base)
        assertEquals(1, result.reaped)
        assertFalse(fileExists(projectDir))
    }

    @Test
    fun nonexistentBaseDirReturnsZero() {
        val result = reapStaleDaemons("/tmp/kolt-reaper-nonexistent-${kotlin.random.Random.nextLong()}")
        assertEquals(0, result.reaped)
        assertEquals(0, result.alive)
    }

    private fun createTempDir(prefix: String): String {
        val template = "/tmp/${prefix}XXXXXX"
        val buf = template.encodeToByteArray().copyOf(template.length + 1)
        buf.usePinned { pinned ->
            val result = mkdtemp(pinned.addressOf(0))
                ?: error("mkdtemp failed")
            return result.toKString()
        }
    }
}
