package keel

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatterTest {

    @Test
    fun formatCommandWithSingleFile() {
        val cmd = formatCommand(
            ktfmtJarPath = "/home/user/.keel/tools/ktfmt-0.54-jar-with-dependencies.jar",
            files = listOf("src/Main.kt"),
            checkOnly = false
        )

        assertEquals(
            listOf(
                "java", "-jar",
                "/home/user/.keel/tools/ktfmt-0.54-jar-with-dependencies.jar",
                "--kotlinlang-style",
                "src/Main.kt"
            ),
            cmd.args
        )
    }

    @Test
    fun formatCommandWithMultipleFiles() {
        val cmd = formatCommand(
            ktfmtJarPath = "/tools/ktfmt.jar",
            files = listOf("src/Main.kt", "src/Config.kt", "test/MainTest.kt"),
            checkOnly = false
        )

        assertEquals(
            listOf(
                "java", "-jar", "/tools/ktfmt.jar",
                "--kotlinlang-style",
                "src/Main.kt", "src/Config.kt", "test/MainTest.kt"
            ),
            cmd.args
        )
    }

    @Test
    fun formatCommandWithEmptyFiles() {
        val cmd = formatCommand(
            ktfmtJarPath = "/tools/ktfmt.jar",
            files = emptyList(),
            checkOnly = false
        )

        assertEquals(
            listOf(
                "java", "-jar", "/tools/ktfmt.jar",
                "--kotlinlang-style"
            ),
            cmd.args
        )
    }

    @Test
    fun formatCommandWithCheckOnly() {
        val cmd = formatCommand(
            ktfmtJarPath = "/tools/ktfmt.jar",
            files = listOf("src/Main.kt"),
            checkOnly = true
        )

        assertEquals(
            listOf(
                "java", "-jar", "/tools/ktfmt.jar",
                "--kotlinlang-style",
                "--set-exit-if-changed",
                "--dry-run",
                "src/Main.kt"
            ),
            cmd.args
        )
    }
}
