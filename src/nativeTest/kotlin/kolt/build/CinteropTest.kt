package kolt.build

import kolt.config.CinteropConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CinteropTest {

    // --- cinteropCommand ---

    @Test
    fun cinteropCommandMinimalProducesDefAndOutput() {
        // Given: a minimal cinterop entry with only name and def
        val entry = CinteropConfig(
            name = "libcurl",
            def = "src/nativeInterop/cinterop/libcurl.def"
        )

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: args contain cinterop binary, -def, and -o flags
        assertEquals(
            listOf("cinterop", "-def", "src/nativeInterop/cinterop/libcurl.def", "-o", "build/libcurl"),
            cmd.args
        )
    }

    @Test
    fun cinteropCommandOutputPathIsInBuildDir() {
        // Given: an entry named "libcurl"
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: -o points to build/<name> (cinterop appends .klib itself)
        assertEquals("build/libcurl", cmd.outputPath)
    }

    @Test
    fun cinteropCommandWithPackageNameEmitsPkgFlag() {
        // Given: entry with packageName set
        val entry = CinteropConfig(
            name = "libcurl",
            def = "libcurl.def",
            packageName = "libcurl"
        )

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: -pkg flag is included
        assertTrue(cmd.args.contains("-pkg"), "Expected -pkg flag in: ${cmd.args}")
        val pkgIndex = cmd.args.indexOf("-pkg")
        assertEquals("libcurl", cmd.args[pkgIndex + 1])
    }

    @Test
    fun cinteropCommandWithoutPackageNameOmitsPkgFlag() {
        // Given: entry with no packageName
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def", packageName = null)

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: -pkg flag is absent
        assertFalse(cmd.args.contains("-pkg"), "Unexpected -pkg flag in: ${cmd.args}")
    }

    @Test
    fun cinteropCommandWithCompilerOptionsEmitsCompilerOptionsFlag() {
        // Given: entry with compilerOptions
        val entry = CinteropConfig(
            name = "libcurl",
            def = "libcurl.def",
            compilerOptions = "-I/usr/include"
        )

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: -compiler-options flag is included with the value
        assertTrue(cmd.args.contains("-compiler-options"), "Expected -compiler-options flag in: ${cmd.args}")
        val flagIndex = cmd.args.indexOf("-compiler-options")
        assertEquals("-I/usr/include", cmd.args[flagIndex + 1])
    }

    @Test
    fun cinteropCommandWithoutCompilerOptionsOmitsFlag() {
        // Given: entry with no compilerOptions
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def", compilerOptions = null)

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: -compiler-options flag is absent
        assertFalse(cmd.args.contains("-compiler-options"), "Unexpected -compiler-options flag in: ${cmd.args}")
    }

    @Test
    fun cinteropCommandWithLinkerOptionsEmitsLinkerOptionsFlag() {
        // Given: entry with linkerOptions
        val entry = CinteropConfig(
            name = "libcurl",
            def = "libcurl.def",
            linkerOptions = "-lcurl"
        )

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: -linker-options flag is included with the value
        assertTrue(cmd.args.contains("-linker-options"), "Expected -linker-options flag in: ${cmd.args}")
        val flagIndex = cmd.args.indexOf("-linker-options")
        assertEquals("-lcurl", cmd.args[flagIndex + 1])
    }

    @Test
    fun cinteropCommandWithoutLinkerOptionsOmitsFlag() {
        // Given: entry with no linkerOptions
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def", linkerOptions = null)

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: -linker-options flag is absent
        assertFalse(cmd.args.contains("-linker-options"), "Unexpected -linker-options flag in: ${cmd.args}")
    }

    @Test
    fun cinteropCommandWithAllOptionsProducesFullArgs() {
        // Given: entry with all optional fields set
        val entry = CinteropConfig(
            name = "libcurl",
            def = "src/nativeInterop/cinterop/libcurl.def",
            packageName = "libcurl",
            compilerOptions = "-I/usr/include",
            linkerOptions = "-lcurl"
        )

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: full args in canonical order
        assertEquals(
            listOf(
                "cinterop",
                "-def", "src/nativeInterop/cinterop/libcurl.def",
                "-o", "build/libcurl",
                "-pkg", "libcurl",
                "-compiler-options", "-I/usr/include",
                "-linker-options", "-lcurl"
            ),
            cmd.args
        )
    }

    @Test
    fun cinteropCommandWithCustomCinteropPath() {
        // Given: a managed cinterop binary path
        val managedPath = "/home/user/.kolt/toolchains/konanc/2.1.0/bin/cinterop"
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        // When: command is constructed with custom cinteropPath
        val cmd = cinteropCommand(entry, cinteropPath = managedPath)

        // Then: managed path is used as the first arg, not system "cinterop"
        assertEquals(managedPath, cmd.args.first())
    }

    @Test
    fun cinteropCommandWithNullCinteropPathDefaultsToSystemCinterop() {
        // Given: no managed path (null)
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        // When: command is constructed with explicit null cinteropPath
        val cmd = cinteropCommand(entry, cinteropPath = null)

        // Then: falls back to system "cinterop"
        assertEquals("cinterop", cmd.args.first())
    }

    @Test
    fun cinteropCommandWithCustomOutputDir() {
        // Given: a custom output directory
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        // When: command is constructed with custom outputDir
        val cmd = cinteropCommand(entry, outputDir = "custom/build")

        // Then: -o uses the custom output dir
        val outputIndex = cmd.args.indexOf("-o")
        assertEquals("custom/build/libcurl", cmd.args[outputIndex + 1])
        assertEquals("custom/build/libcurl", cmd.outputPath)
    }

    @Test
    fun cinteropCommandOutputPathDoesNotIncludeKlibExtension() {
        // cinterop tool appends .klib itself; we must not double-append it
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        val cmd = cinteropCommand(entry)

        assertFalse(cmd.outputPath.endsWith(".klib.klib"), "outputPath must not double .klib: ${cmd.outputPath}")
        assertFalse(cmd.args.any { it.endsWith(".klib") }, "No .klib suffix expected in args: ${cmd.args}")
    }

    // --- cinteropOutputKlibPath ---

    @Test
    fun cinteropOutputKlibPathReturnsExpectedPath() {
        // Given: an entry named "libcurl"
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        // When: klib path is computed
        val path = cinteropOutputKlibPath(entry)

        // Then: path is build/<name>.klib
        assertEquals("build/libcurl.klib", path)
    }

    @Test
    fun cinteropOutputKlibPathWithCustomOutputDir() {
        // Given: a custom output directory
        val entry = CinteropConfig(name = "libssl", def = "libssl.def")

        // When: klib path is computed with custom dir
        val path = cinteropOutputKlibPath(entry, outputDir = "custom/build")

        // Then: path uses custom dir
        assertEquals("custom/build/libssl.klib", path)
    }

    @Test
    fun cinteropOutputKlibPathUsesEntryName() {
        // Given: two entries with different names
        val curl = CinteropConfig(name = "libcurl", def = "libcurl.def")
        val ssl = CinteropConfig(name = "openssl", def = "openssl.def")

        // When: klib paths are computed
        val curlPath = cinteropOutputKlibPath(curl)
        val sslPath = cinteropOutputKlibPath(ssl)

        // Then: paths differ by name
        assertEquals("build/libcurl.klib", curlPath)
        assertEquals("build/openssl.klib", sslPath)
    }
}
