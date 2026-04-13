package kolt.build

import com.github.michaelbull.result.get
import kolt.config.KoltPaths
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.executeAndCapture
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.homeDirectory
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import platform.posix.getpid

// E2E smoke test: exercises the full cinterop → konanc → kexe pipeline against
// a real libcurl fixture. Requires the managed konanc toolchain (2.1.0) and
// libcurl headers (curl/curl.h) to be present at one of several well-known
// locations. Skips when either prerequisite is missing.
class CinteropSmokeTest {

    // Candidate include directories that may contain curl/curl.h. Covers:
    // - Debian/Ubuntu multiarch (/usr/include/x86_64-linux-gnu)
    // - Default /usr/include (Fedora/Arch/Alpine and most other Linux)
    // - /usr/local/include (source/manual installs)
    private val curlIncludeCandidates = listOf(
        "/usr/include/x86_64-linux-gnu",
        "/usr/include",
        "/usr/local/include",
    )

    // Candidate library directories that may contain libcurl.so. Matches the
    // same host layouts as the include candidates above.
    private val curlLibCandidates = listOf(
        "/usr/lib/x86_64-linux-gnu",
        "/usr/lib64",
        "/usr/lib",
        "/usr/local/lib",
    )

    @Test
    fun libcurlCinteropPipelineProducesRunningExecutable() {
        val home = homeDirectory().get() ?: error("HOME not set")
        val paths = KoltPaths(home)
        val kotlinVersion = "2.1.0"
        val cinteropBin = paths.cinteropBin(kotlinVersion)
        val konancBin = paths.konancBin(kotlinVersion)

        // Skip when managed toolchain is absent (CI without pre-installed konanc).
        if (!fileExists(cinteropBin) || !fileExists(konancBin)) {
            println("SKIP: managed konanc toolchain not found at ${paths.konancPath(kotlinVersion)}")
            return
        }

        // Skip when libcurl headers are absent (non-Debian hosts without libcurl-dev).
        // We always include /usr/include (base glibc headers) and add the directory
        // holding curl/curl.h if it differs — on Debian/Ubuntu that's the multiarch path.
        val curlIncludeDir = curlIncludeCandidates.firstOrNull { fileExists("$it/curl/curl.h") }
        if (curlIncludeDir == null) {
            println(
                "SKIP: libcurl headers (curl/curl.h) not found in any of " +
                    curlIncludeCandidates.joinToString()
            )
            return
        }
        val includeDirs = linkedSetOf("/usr/include", curlIncludeDir).toList()
        val compilerOpts = includeDirs.joinToString(" ") { "-I$it" }

        val curlLibDir = curlLibCandidates.firstOrNull { fileExists("$it/libcurl.so") }
        if (curlLibDir == null) {
            println(
                "SKIP: libcurl.so not found in any of " + curlLibCandidates.joinToString()
            )
            return
        }
        val linkerOpts = "-L$curlLibDir -lcurl"

        val tmpDir = "/tmp/kolt-e2e-cinterop-${getpid()}"
        val buildDir = "$tmpDir/build"
        ensureDirectoryRecursive(buildDir)

        try {
            // --- Fixture: libcurl.def ---
            val defFile = "$tmpDir/libcurl.def"
            writeFileAsString(
                defFile,
                """
                headers = curl/curl.h
                compilerOpts.linux = $compilerOpts
                linkerOpts.linux = $linkerOpts
                """.trimIndent()
            )

            // --- Fixture: Main.kt (calls curl_version()) ---
            val mainFile = "$tmpDir/Main.kt"
            writeFileAsString(
                mainFile,
                """
                import libcurl.*
                import kotlinx.cinterop.ExperimentalForeignApi
                import kotlinx.cinterop.toKString

                @OptIn(ExperimentalForeignApi::class)
                fun main() {
                    val version = curl_version()?.toKString() ?: "unknown"
                    println(version)
                }
                """.trimIndent()
            )

            // --- Step 1: cinterop — .def → libcurl.klib ---
            val klibBase = "$buildDir/libcurl"
            val cinteropResult = executeCommand(listOf(cinteropBin, "-def", defFile, "-o", klibBase))
            assertNotNull(
                cinteropResult.get(),
                "cinterop failed to generate klib: $cinteropResult"
            )
            val klibFile = "$klibBase.klib"
            assertTrue(fileExists(klibFile), "Expected .klib at $klibFile after cinterop")

            // --- Step 2: konanc stage 1 — sources + cinterop klib → project klib ---
            val appKlibBase = "$buildDir/smoke-klib"
            val stage1Result = executeCommand(
                listOf(
                    konancBin, mainFile,
                    "-p", "library", "-nopack",
                    "-l", klibFile,
                    "-o", appKlibBase
                )
            )
            assertNotNull(
                stage1Result.get(),
                "konanc stage 1 (compile to klib) failed: $stage1Result"
            )
            assertTrue(fileExists(appKlibBase), "Expected project klib at $appKlibBase")

            // --- Step 3: konanc stage 2 — project klib → executable ---
            val exeBase = "$buildDir/smoke"
            val stage2Result = executeCommand(
                listOf(
                    konancBin,
                    "-p", "program",
                    "-l", klibFile,
                    "-Xinclude=$appKlibBase",
                    "-o", exeBase
                )
            )
            assertNotNull(
                stage2Result.get(),
                "konanc stage 2 (link to kexe) failed: $stage2Result"
            )
            val exeFile = "$exeBase.kexe"
            assertTrue(fileExists(exeFile), "Expected executable at $exeFile after link")

            // --- Step 4: run and verify curl_version() output ---
            val runResult = executeAndCapture("$exeFile 2>&1")
            val output = assertNotNull(runResult.get(), "executable run failed: $runResult")
            assertTrue(
                output.contains("curl/"),
                "Expected curl version string (e.g. 'curl/8.x.y') in output, got: $output"
            )
        } finally {
            removeDirectoryRecursive(tmpDir)
        }
    }
}
