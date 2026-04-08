package keel

import kotlin.test.Test
import kotlin.test.assertEquals

class RunnerTest {

    @Test
    fun runCommandProducesCorrectArgs() {
        val cmd = runCommand(testConfig())

        assertEquals(listOf("java", "-jar", "build/my-app.jar"), cmd.args)
        assertEquals("build/my-app.jar", cmd.jarPath)
    }

    @Test
    fun runCommandUsesProjectName() {
        val cmd = runCommand(testConfig(name = "hello-world"))
        assertEquals("build/hello-world.jar", cmd.jarPath)
    }
}
