package keel.build

import kotlin.test.Test
import kotlin.test.assertEquals

class TestRunnerTest {

    @Test
    fun testRunCommandBasic() {
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/home/user/.keel/tools/junit-platform-console-standalone-1.11.4.jar"
        )

        assertEquals(
            listOf(
                "java", "-jar",
                "/home/user/.keel/tools/junit-platform-console-standalone-1.11.4.jar",
                "--class-path", "build/classes:build/test-classes",
                "--scan-class-path"
            ),
            cmd.args
        )
    }

    @Test
    fun testRunCommandWithClasspath() {
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            classpath = "/cache/dep.jar:/cache/junit.jar"
        )

        assertEquals(
            listOf(
                "java", "-jar", "/tools/launcher.jar",
                "--class-path", "build/classes:build/test-classes:/cache/dep.jar:/cache/junit.jar",
                "--scan-class-path"
            ),
            cmd.args
        )
    }

    @Test
    fun testRunCommandWithTestArgs() {
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            testArgs = listOf("--include-classname", ".*Test")
        )

        assertEquals(
            listOf(
                "java", "-jar", "/tools/launcher.jar",
                "--class-path", "build/classes:build/test-classes",
                "--scan-class-path",
                "--include-classname", ".*Test"
            ),
            cmd.args
        )
    }

    @Test
    fun testRunCommandWithEmptyClasspath() {
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            classpath = ""
        )

        assertEquals(
            listOf(
                "java", "-jar", "/tools/launcher.jar",
                "--class-path", "build/classes:build/test-classes",
                "--scan-class-path"
            ),
            cmd.args
        )
    }
}
