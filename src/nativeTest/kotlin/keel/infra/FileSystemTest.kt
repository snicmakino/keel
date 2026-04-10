package keel.infra

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

    @Test
    fun writeFileAsStringCreatesFile() {
        val path = "/tmp/keel_test_write.txt"
        remove(path)
        try {
            val result = writeFileAsString(path, "hello write")
            assertNotNull(result.get())
            assertEquals("hello write", assertNotNull(readFileAsString(path).get()))
        } finally {
            remove(path)
        }
    }

    @Test
    fun writeFileAsStringOverwritesExisting() {
        val path = "/tmp/keel_test_write_overwrite.txt"
        writeTestFile(path, "old content")
        try {
            writeFileAsString(path, "new content")
            assertEquals("new content", assertNotNull(readFileAsString(path).get()))
        } finally {
            remove(path)
        }
    }

    @Test
    fun writeFileAsStringReturnsErrOnInvalidPath() {
        val result = writeFileAsString("/nonexistent_root/file.txt", "data")
        assertIs<WriteFailed>(result.getError())
    }

    @Test
    fun ensureDirectoryRecursiveCreatesNestedDirs() {
        val base = "/tmp/keel_test_recursive"
        val path = "$base/a/b/c"
        try {
            val result = ensureDirectoryRecursive(path)
            assertNotNull(result.get())
            assertTrue(fileExists(path))
        } finally {
            platform.posix.rmdir("$base/a/b/c")
            platform.posix.rmdir("$base/a/b")
            platform.posix.rmdir("$base/a")
            platform.posix.rmdir(base)
        }
    }

    @Test
    fun ensureDirectoryRecursiveSucceedsWhenAlreadyExists() {
        val path = "/tmp/keel_test_recursive_exists"
        platform.posix.mkdir(path, 0b111111101u)
        try {
            val result = ensureDirectoryRecursive(path)
            assertNotNull(result.get())
        } finally {
            platform.posix.rmdir(path)
        }
    }

    @Test
    fun homeDirectoryReturnsOk() {
        val result = homeDirectory()
        val home = assertNotNull(result.get())
        assertTrue(home.isNotEmpty())
        assertTrue(fileExists(home))
    }

    @Test
    fun fileMtimeReturnsValueForExistingFile() {
        val path = "/tmp/keel_test_mtime.txt"
        writeTestFile(path, "mtime test")
        try {
            val mtime = fileMtime(path)
            assertNotNull(mtime)
            assertTrue(mtime > 0L)
        } finally {
            remove(path)
        }
    }

    @Test
    fun fileMtimeReturnsNullForNonExistentFile() {
        assertNull(fileMtime("/tmp/keel_nonexistent_mtime.txt"))
    }

    @Test
    fun newestMtimeReturnsNewestFileInDirectory() {
        val dir = "/tmp/keel_test_newest_mtime"
        platform.posix.mkdir(dir, 0b111111101u)
        writeTestFile("$dir/a.kt", "a")
        // ext4 has 1-second mtime granularity; sleep to ensure distinct mtimes
        platform.posix.sleep(1u)
        writeTestFile("$dir/b.kt", "b")
        try {
            val newest = newestMtime(listOf(dir))
            val bMtime = fileMtime("$dir/b.kt")
            assertNotNull(newest)
            assertNotNull(bMtime)
            assertEquals(bMtime, newest)
        } finally {
            remove("$dir/a.kt")
            remove("$dir/b.kt")
            platform.posix.rmdir(dir)
        }
    }

    @Test
    fun newestMtimeReturnsZeroForEmptyDirectory() {
        val dir = "/tmp/keel_test_newest_empty"
        platform.posix.mkdir(dir, 0b111111101u)
        try {
            val newest = newestMtime(listOf(dir))
            assertEquals(0L, newest)
        } finally {
            platform.posix.rmdir(dir)
        }
    }

    @Test
    fun newestMtimeAllReturnsNewestFileInDirectory() {
        val dir = "/tmp/keel_test_newest_mtime_all"
        platform.posix.mkdir(dir, 0b111111101u)
        writeTestFile("$dir/a.txt", "a")
        // ext4 has 1-second mtime granularity; sleep to ensure distinct mtimes
        platform.posix.sleep(1u)
        writeTestFile("$dir/b.class", "b")
        try {
            val newest = newestMtimeAll(dir)
            val bMtime = fileMtime("$dir/b.class")
            assertNotNull(bMtime)
            assertEquals(bMtime, newest)
        } finally {
            remove("$dir/a.txt")
            remove("$dir/b.class")
            platform.posix.rmdir(dir)
        }
    }

    @Test
    fun newestMtimeAllReturnsZeroForEmptyDirectory() {
        val dir = "/tmp/keel_test_newest_all_empty"
        platform.posix.mkdir(dir, 0b111111101u)
        try {
            assertEquals(0L, newestMtimeAll(dir))
        } finally {
            platform.posix.rmdir(dir)
        }
    }

    @Test
    fun newestMtimeAllReturnsZeroForNonExistentDirectory() {
        assertEquals(0L, newestMtimeAll("/tmp/keel_nonexistent_dir_mtime_all"))
    }

    @Test
    fun newestMtimeAllRecursesIntoSubdirectories() {
        val dir = "/tmp/keel_test_newest_all_recursive"
        val sub = "$dir/sub"
        platform.posix.mkdir(dir, 0b111111101u)
        platform.posix.mkdir(sub, 0b111111101u)
        writeTestFile("$dir/a.txt", "a")
        // ext4 has 1-second mtime granularity; sleep to ensure distinct mtimes
        platform.posix.sleep(1u)
        writeTestFile("$sub/b.class", "b")
        try {
            val newest = newestMtimeAll(dir)
            val bMtime = fileMtime("$sub/b.class")
            assertNotNull(bMtime)
            assertEquals(bMtime, newest)
        } finally {
            remove("$dir/a.txt")
            remove("$sub/b.class")
            platform.posix.rmdir(sub)
            platform.posix.rmdir(dir)
        }
    }

    private fun writeTestFile(path: String, content: String) {
        val fp = platform.posix.fopen(path, "w") ?: error("could not create test file: $path")
        if (content.isNotEmpty()) {
            platform.posix.fputs(content, fp)
        }
        platform.posix.fclose(fp)
    }
}
