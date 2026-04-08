package keel

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.remove
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class FileSystemTest {

    @Test
    fun readExistingFileReturnsOk() {
        val path = "/tmp/keel_test_read.txt"
        writeTestFile(path, "hello keel")
        try {
            val result = readFileAsString(path)

            val content = assertNotNull(result.get())
            assertEquals("hello keel", content)
        } finally {
            remove(path)
        }
    }

    @Test
    fun readEmptyFileReturnsOk() {
        val path = "/tmp/keel_test_empty.txt"
        writeTestFile(path, "")
        try {
            val result = readFileAsString(path)

            val content = assertNotNull(result.get())
            assertEquals("", content)
        } finally {
            remove(path)
        }
    }

    @Test
    fun readNonExistentFileReturnsErr() {
        val result = readFileAsString("/tmp/keel_nonexistent_file.txt")

        assertNull(result.get())
        assertIs<OpenFailed>(result.getError())
    }

    @Test
    fun fileExistsReturnsTrueForExistingFile() {
        val path = "/tmp/keel_test_exists.txt"
        writeTestFile(path, "x")
        try {
            assertTrue(fileExists(path))
        } finally {
            remove(path)
        }
    }

    @Test
    fun fileExistsReturnsFalseForNonExistentFile() {
        assertFalse(fileExists("/tmp/keel_nonexistent_file.txt"))
    }

    @Test
    fun ensureDirectoryCreatesNewDirectory() {
        val path = "/tmp/keel_test_ensure_dir"
        remove(path)
        try {
            val result = ensureDirectory(path)

            assertNotNull(result.get())
            assertTrue(fileExists(path))
        } finally {
            platform.posix.rmdir(path)
        }
    }

    @Test
    fun ensureDirectorySucceedsWhenAlreadyExists() {
        val path = "/tmp/keel_test_ensure_dir_exists"
        platform.posix.mkdir(path, 0b111111101u)
        try {
            val result = ensureDirectory(path)

            assertNotNull(result.get())
        } finally {
            platform.posix.rmdir(path)
        }
    }

    @Test
    fun ensureDirectoryReturnsErrOnInvalidPath() {
        val result = ensureDirectory("/nonexistent_root/subdir")

        assertNull(result.get())
        assertIs<MkdirFailed>(result.getError())
    }

    private fun writeTestFile(path: String, content: String) {
        val fp = platform.posix.fopen(path, "w") ?: error("could not create test file: $path")
        if (content.isNotEmpty()) {
            platform.posix.fputs(content, fp)
        }
        platform.posix.fclose(fp)
    }
}
