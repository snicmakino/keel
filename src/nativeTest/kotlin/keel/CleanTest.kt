package keel

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CleanTest {
    @Test
    fun removeDirectoryDeletesDirectoryAndContents() {
        val dir = "build_test_clean"
        ensureDirectoryRecursive("$dir/sub")
        writeFileAsString("$dir/file.txt", "hello")
        writeFileAsString("$dir/sub/nested.txt", "world")
        assertTrue(fileExists(dir))

        val result = removeDirectoryRecursive(dir)
        assertNotNull(result.get())
        assertFalse(fileExists(dir))
    }

    @Test
    fun removeDirectoryReturnsErrorForNonExistentPath() {
        val result = removeDirectoryRecursive("nonexistent_dir_xyz")
        val error = result.getError()
        assertNotNull(error)
        assertEquals("nonexistent_dir_xyz", error.path)
    }
}
