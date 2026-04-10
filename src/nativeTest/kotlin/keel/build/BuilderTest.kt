package keel.build

import keel.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BuilderTest {

    // --- buildCommand ---

    @Test
    fun buildCommandOutputsToClassesDir() {
        val cmd = buildCommand(testConfig())

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "17", "-d", "build/classes"),
            cmd.args
        )
        assertEquals("build/classes", cmd.outputPath)
    }

    @Test
    fun buildCommandDoesNotIncludeRuntime() {
        val cmd = buildCommand(testConfig())

        assertFalse(cmd.args.contains("-include-runtime"))
    }

    @Test
    fun buildCommandMultipleSources() {
        val cmd = buildCommand(testConfig(sources = listOf("src", "generated")))

        assertEquals(
            listOf("kotlinc", "src", "generated", "-jvm-target", "17", "-d", "build/classes"),
            cmd.args
        )
    }

    @Test
    fun buildCommandWithClasspath() {
        val cmd = buildCommand(testConfig(), classpath = "/cache/a.jar:/cache/b.jar")

        assertEquals(
            listOf("kotlinc", "-cp", "/cache/a.jar:/cache/b.jar", "src", "-jvm-target", "17", "-d", "build/classes"),
            cmd.args
        )
    }

    @Test
    fun buildCommandDifferentJvmTarget() {
        val cmd = buildCommand(testConfig(jvmTarget = "21"))

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "21", "-d", "build/classes"),
            cmd.args
        )
    }

    @Test
    fun buildCommandOutputPathIsClassesDir() {
        // outputPath is always build/classes regardless of project name
        val cmd = buildCommand(testConfig(name = "hello-world"))
        assertEquals("build/classes", cmd.outputPath)
    }

    @Test
    fun buildCommandEmptyClasspathIsIgnored() {
        val cmd = buildCommand(testConfig(), classpath = "")

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "17", "-d", "build/classes"),
            cmd.args
        )
    }

    @Test
    fun buildCommandEmptySources() {
        val cmd = buildCommand(testConfig(sources = emptyList()))

        assertEquals(
            listOf("kotlinc", "-jvm-target", "17", "-d", "build/classes"),
            cmd.args
        )
    }

    @Test
    fun buildCommandWithPluginArgs() {
        val pluginArgs = listOf("-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar")

        val cmd = buildCommand(testConfig(), pluginArgs = pluginArgs)

        assertEquals(
            listOf(
                "kotlinc", "src", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
                "-d", "build/classes"
            ),
            cmd.args
        )
    }

    @Test
    fun buildCommandWithMultiplePluginArgs() {
        val pluginArgs = listOf(
            "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
            "-Xplugin=/usr/local/kotlinc/lib/allopen-compiler-plugin.jar"
        )

        val cmd = buildCommand(testConfig(), pluginArgs = pluginArgs)

        assertEquals(
            listOf(
                "kotlinc", "src", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
                "-Xplugin=/usr/local/kotlinc/lib/allopen-compiler-plugin.jar",
                "-d", "build/classes"
            ),
            cmd.args
        )
    }

    @Test
    fun buildCommandWithEmptyPluginArgs() {
        val cmd = buildCommand(testConfig(), pluginArgs = emptyList())

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "17", "-d", "build/classes"),
            cmd.args
        )
    }

    @Test
    fun buildCommandWithClasspathAndPluginArgs() {
        val pluginArgs = listOf("-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar")

        val cmd = buildCommand(testConfig(), classpath = "/cache/a.jar", pluginArgs = pluginArgs)

        assertEquals(
            listOf(
                "kotlinc", "-cp", "/cache/a.jar", "src", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
                "-d", "build/classes"
            ),
            cmd.args
        )
    }

    // --- checkCommand ---

    @Test
    fun checkCommandDoesNotIncludeRuntimeOrOutput() {
        val cmd = checkCommand(testConfig())

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "17"),
            cmd
        )
    }

    @Test
    fun checkCommandWithClasspath() {
        val cmd = checkCommand(testConfig(), classpath = "/cache/a.jar")

        assertEquals(
            listOf("kotlinc", "-cp", "/cache/a.jar", "src", "-jvm-target", "17"),
            cmd
        )
    }

    @Test
    fun checkCommandWithPluginArgs() {
        val pluginArgs = listOf("-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar")

        val cmd = checkCommand(testConfig(), pluginArgs = pluginArgs)

        assertEquals(
            listOf(
                "kotlinc", "src", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"
            ),
            cmd
        )
    }

    @Test
    fun checkCommandWithEmptyPluginArgs() {
        val cmd = checkCommand(testConfig(), pluginArgs = emptyList())

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "17"),
            cmd
        )
    }

    // --- jarCommand ---

    @Test
    fun jarCommandPackagesClassesDirToJar() {
        val cmd = jarCommand(testConfig())

        assertEquals(
            listOf("jar", "cf", "build/my-app.jar", "-C", "build/classes", "."),
            cmd.args
        )
        assertEquals("build/my-app.jar", cmd.outputPath)
    }

    @Test
    fun jarCommandOutputPathUsesProjectName() {
        val cmd = jarCommand(testConfig(name = "hello-world"))

        assertEquals("build/hello-world.jar", cmd.outputPath)
        assertEquals(
            listOf("jar", "cf", "build/hello-world.jar", "-C", "build/classes", "."),
            cmd.args
        )
    }

    @Test
    fun jarCommandSourceDirectoryIsAlwaysClassesDir() {
        // jar source is always build/classes regardless of project name
        val cmd1 = jarCommand(testConfig(name = "app-a"))
        val cmd2 = jarCommand(testConfig(name = "app-b"))

        assertEquals("build/classes", cmd1.args[4])
        assertEquals("build/classes", cmd2.args[4])
    }
}
