package keel.build

import keel.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class RunnerTest {

    @Test
    fun runCommandUsesClassesDirAsClasspath() {
        val cmd = runCommand(testConfig(), null)
        assertEquals(listOf("java", "-cp", "build/classes", "com.example.MainKt"), cmd.args)
    }

    @Test
    fun runCommandWithClasspathAppendedToClassesDir() {
        val cmd = runCommand(testConfig(), "/cache/lib.jar:/cache/util.jar")
        assertEquals(
            listOf("java", "-cp", "build/classes:/cache/lib.jar:/cache/util.jar", "com.example.MainKt"),
            cmd.args
        )
    }

    @Test
    fun runCommandWithAppArgs() {
        val cmd = runCommand(testConfig(), null, listOf("--port", "8080"))
        assertEquals(
            listOf("java", "-cp", "build/classes", "com.example.MainKt", "--port", "8080"),
            cmd.args
        )
    }

    @Test
    fun runCommandWithEmptyAppArgs() {
        val cmd = runCommand(testConfig(), null, emptyList())
        assertEquals(listOf("java", "-cp", "build/classes", "com.example.MainKt"), cmd.args)
    }

    @Test
    fun runCommandEmptyClasspathIsIgnored() {
        val cmd = runCommand(testConfig(), "")
        assertEquals(listOf("java", "-cp", "build/classes", "com.example.MainKt"), cmd.args)
    }
}
