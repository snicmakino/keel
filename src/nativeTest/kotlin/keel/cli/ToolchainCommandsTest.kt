package keel.cli

import keel.config.KeelPaths
import keel.infra.ensureDirectoryRecursive
import keel.infra.removeDirectoryRecursive
import keel.infra.writeFileAsString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FormatToolchainListTest {

    @Test
    fun noToolchainsInstalledReturnsMessage() {
        // Given: no toolchains installed
        val kotlincVersions = emptyList<String>()
        val jdkVersions = emptyList<String>()

        // When: formatting the list
        val result = formatToolchainList(kotlincVersions, jdkVersions)

        // Then: returns "no toolchains installed" message
        assertEquals("no toolchains installed", result)
    }

    @Test
    fun onlyKotlincInstalled() {
        // Given: kotlinc versions installed
        val kotlincVersions = listOf("2.1.0", "2.3.0")
        val jdkVersions = emptyList<String>()

        // When: formatting the list
        val result = formatToolchainList(kotlincVersions, jdkVersions)

        // Then: shows kotlinc section only
        val expected = """
            kotlinc:
              2.1.0
              2.3.0
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun onlyJdkInstalled() {
        // Given: jdk versions installed
        val kotlincVersions = emptyList<String>()
        val jdkVersions = listOf("21")

        // When: formatting the list
        val result = formatToolchainList(kotlincVersions, jdkVersions)

        // Then: shows jdk section only
        val expected = """
            jdk:
              21
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun bothKotlincAndJdkInstalled() {
        // Given: both kotlinc and jdk installed
        val kotlincVersions = listOf("2.1.0")
        val jdkVersions = listOf("17", "21")

        // When: formatting the list
        val result = formatToolchainList(kotlincVersions, jdkVersions)

        // Then: shows both sections separated by blank line
        val expected = """
            kotlinc:
              2.1.0

            jdk:
              17
              21
        """.trimIndent()
        assertEquals(expected, result)
    }
}

class ValidateToolchainRemoveArgsTest {

    @Test
    fun validKotlincArgs() {
        // Given: valid kotlinc remove args
        val result = validateToolchainRemoveArgs(listOf("kotlinc", "2.1.0"))

        // Then: returns valid parsed args
        val args = assertNotNull(result.first)
        assertEquals("kotlinc", args.name)
        assertEquals("2.1.0", args.version)
        assertNull(result.second)
    }

    @Test
    fun validJdkArgs() {
        // Given: valid jdk remove args
        val result = validateToolchainRemoveArgs(listOf("jdk", "21"))

        // Then: returns valid parsed args
        val args = assertNotNull(result.first)
        assertEquals("jdk", args.name)
        assertEquals("21", args.version)
        assertNull(result.second)
    }

    @Test
    fun unknownToolchainNameReturnsError() {
        // Given: unknown toolchain name
        val result = validateToolchainRemoveArgs(listOf("foo", "1.0"))

        // Then: returns error message
        assertNull(result.first)
        assertEquals("error: unknown toolchain 'foo' (available: kotlinc, jdk)", result.second)
    }

    @Test
    fun missingArgsReturnsError() {
        // Given: not enough args
        val result = validateToolchainRemoveArgs(listOf("kotlinc"))

        // Then: returns usage error
        assertNull(result.first)
        assertEquals("usage: keel toolchain remove <name> <version>", result.second)
    }

    @Test
    fun emptyArgsReturnsError() {
        // Given: no args
        val result = validateToolchainRemoveArgs(emptyList())

        // Then: returns usage error
        assertNull(result.first)
        assertEquals("usage: keel toolchain remove <name> <version>", result.second)
    }
}

class ResolveToolchainPathForRemoveTest {

    @Test
    fun kotlincInstalledReturnsPath() {
        // Given: kotlinc 2.1.0 is installed
        val paths = KeelPaths("/tmp/keel_tc_remove_kotlinc")
        val binDir = "${paths.toolchainsDir}/kotlinc/2.1.0/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/kotlinc", "#!/bin/sh")
        try {
            // When: resolving path for removal
            val result = resolveToolchainPathForRemove("kotlinc", "2.1.0", paths)

            // Then: returns the toolchain directory path
            assertEquals("${paths.toolchainsDir}/kotlinc/2.1.0", result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun jdkInstalledReturnsPath() {
        // Given: jdk 21 is installed
        val paths = KeelPaths("/tmp/keel_tc_remove_jdk")
        val binDir = "${paths.toolchainsDir}/jdk/21/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/java", "#!/bin/sh")
        try {
            // When: resolving path for removal
            val result = resolveToolchainPathForRemove("jdk", "21", paths)

            // Then: returns the toolchain directory path
            assertEquals("${paths.toolchainsDir}/jdk/21", result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun notInstalledReturnsNull() {
        // Given: kotlinc 2.1.0 is not installed
        val paths = KeelPaths("/tmp/keel_tc_remove_not_installed")

        // When: resolving path for removal
        val result = resolveToolchainPathForRemove("kotlinc", "2.1.0", paths)

        // Then: returns null
        assertNull(result)
    }
}
