package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.fileExists
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.PATH_MAX
import platform.posix.chdir
import platform.posix.getcwd
import platform.posix.mkdtemp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class DoInitTest {
    private var originalCwd: String = ""
    private var tmpDir: String = ""

    @BeforeTest
    fun setUp() {
        originalCwd = memScoped {
            val buf = allocArray<ByteVar>(PATH_MAX)
            getcwd(buf, PATH_MAX.toULong())?.toKString() ?: error("getcwd failed")
        }
        tmpDir = createTempDir("kolt-init-")
        check(chdir(tmpDir) == 0) { "chdir to $tmpDir failed" }
    }

    @AfterTest
    fun tearDown() {
        chdir(originalCwd)
        if (tmpDir.isNotEmpty() && fileExists(tmpDir)) {
            removeDirectoryRecursive(tmpDir)
        }
    }

    @Test
    fun writesGitignoreWhenAbsent() {
        doInit(listOf("my-app")).getOrElse { error("doInit failed: exit=$it") }

        assertTrue(fileExists(".gitignore"))
    }

    @Test
    fun leavesExistingGitignoreUntouched() {
        writeFileAsString(".gitignore", "custom\n").getOrElse { error("seed failed") }

        doInit(listOf("my-app")).getOrElse { error("doInit failed: exit=$it") }

        val content = kolt.infra.readFileAsString(".gitignore").getOrElse { error("read failed") }
        assertEquals("custom\n", content)
    }

    @Test
    fun runsGitInitWhenNoGitDir() {
        doInit(listOf("my-app")).getOrElse { error("doInit failed: exit=$it") }

        assertTrue(fileExists(".git"), ".git/ must exist after kolt init")
    }

    @Test
    fun leavesExistingGitRepoUntouched() {
        ensureDirectoryRecursive(".git").getOrElse { error("seed .git failed") }
        writeFileAsString(".git/marker", "keep me").getOrElse { error("seed marker failed") }

        doInit(listOf("my-app")).getOrElse { error("doInit failed: exit=$it") }

        assertTrue(fileExists(".git/marker"), "existing .git/ must be left alone")
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
