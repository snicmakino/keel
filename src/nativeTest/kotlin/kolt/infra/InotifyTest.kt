package kolt.infra

import com.github.michaelbull.result.getOrElse
import platform.linux.IN_CREATE
import platform.linux.IN_MODIFY
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class InotifyWatcherTest {

    @Test
    fun createAndClose() {
        val watcher = InotifyWatcher.create().getOrElse { fail("create failed: $it") }
        watcher.close()
    }

    @Test
    fun watchNonexistentPathFails() {
        val watcher = InotifyWatcher.create().getOrElse { fail("create failed: $it") }
        try {
            val result = watcher.addWatch("/nonexistent_${getpid()}", IN_MODIFY.toUInt())
            assertTrue(result.isErr)
        } finally {
            watcher.close()
        }
    }

    @Test
    fun detectsFileCreation() {
        val dir = "/tmp/kolt_inotify_test_${getpid()}"
        ensureDirectoryRecursive(dir).getOrElse { fail("mkdir failed") }
        try {
            val watcher = InotifyWatcher.create().getOrElse { fail("create failed: $it") }
            try {
                watcher.addWatch(dir, (IN_CREATE or IN_MODIFY).toUInt())
                    .getOrElse { fail("addWatch failed: $it") }

                writeFileAsString("$dir/hello.kt", "fun main() {}")
                    .getOrElse { fail("write failed") }

                val events = watcher.pollEvents(timeoutMs = 3000)
                    .getOrElse { fail("pollEvents failed: $it") }
                assertTrue(events.isNotEmpty(), "expected at least one event")
                val names = events.map { it.name }
                assertTrue("hello.kt" in names, "expected hello.kt in events, got: $names")
            } finally {
                watcher.close()
            }
        } finally {
            removeDirectoryRecursive(dir)
        }
    }

    @Test
    fun addWatchRecursiveCoversSubdirs() {
        val dir = "/tmp/kolt_inotify_rec_${getpid()}"
        ensureDirectoryRecursive("$dir/sub/deep").getOrElse { fail("mkdir failed") }
        try {
            val watcher = InotifyWatcher.create().getOrElse { fail("create failed: $it") }
            try {
                watcher.addWatchRecursive(dir).getOrElse { fail("addWatchRecursive failed: $it") }

                writeFileAsString("$dir/sub/deep/Nested.kt", "class Nested")
                    .getOrElse { fail("write failed") }

                val events = watcher.pollEvents(timeoutMs = 3000)
                    .getOrElse { fail("pollEvents failed: $it") }
                assertTrue(events.isNotEmpty(), "expected at least one event from subdirectory")
                val names = events.map { it.name }
                assertTrue("Nested.kt" in names, "expected Nested.kt, got: $names")
            } finally {
                watcher.close()
            }
        } finally {
            removeDirectoryRecursive(dir)
        }
    }

    @Test
    fun pollReturnsEmptyOnTimeout() {
        val dir = "/tmp/kolt_inotify_empty_${getpid()}"
        ensureDirectoryRecursive(dir).getOrElse { fail("mkdir failed") }
        try {
            val watcher = InotifyWatcher.create().getOrElse { fail("create failed: $it") }
            try {
                watcher.addWatch(dir, IN_MODIFY.toUInt())
                    .getOrElse { fail("addWatch failed: $it") }
                val events = watcher.pollEvents(timeoutMs = 50)
                    .getOrElse { fail("pollEvents failed: $it") }
                assertTrue(events.isEmpty(), "expected no events on timeout")
            } finally {
                watcher.close()
            }
        } finally {
            removeDirectoryRecursive(dir)
        }
    }
}
