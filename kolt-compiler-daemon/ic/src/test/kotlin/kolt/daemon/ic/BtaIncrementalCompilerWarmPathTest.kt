@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kolt.daemon.ic

import com.github.michaelbull.result.getOrElse
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

// Exercises the B-2b incremental path of BtaIncrementalCompiler: cold
// compile, modify source, warm compile, and confirm BTA wrote IC state
// under the caller-owned `workingDir`. Structurally this is the ADR
// 0019 §5 "daemon-owned state that survives across requests" claim made
// testable with a real BTA toolchain.
//
// This test deliberately does **not** assert a specific recompile-set
// size. The size-based assertion (1 class file recompiled on ABI-neutral
// edits for linear-10 / hub-10) lives in a separate integration test
// that pulls the spike fixtures into the `:ic` test task — see task #8
// / a later commit. Keeping this test shape-only lets it run in every
// `./gradlew :kolt-compiler-daemon:ic:test` cycle without dragging the
// spike fixtures into the daemon module's test classpath.
class BtaIncrementalCompilerWarmPathTest {

    private val btaImplJars: List<Path> = systemClasspath("kolt.ic.btaImplClasspath")
    private val fixtureClasspath: List<Path> = systemClasspath("kolt.ic.fixtureClasspath")

    @Test
    fun `workingDir is created lazily if it does not exist when compile is called`() {
        val workRoot = Files.createTempDirectory("bta-warm-lazy-")
        val sourceFile = workRoot.resolve("Main.kt").also {
            it.writeText(
                """
                package fixture
                object Main { fun greeting(): String = "hello" }
                """.trimIndent(),
            )
        }
        val outputDir = workRoot.resolve("classes").apply { createDirectories() }
        // Intentionally NOT created — the adapter must materialise it.
        val workingDir = workRoot.resolve("ic-state")
        assertTrue(!Files.exists(workingDir), "precondition: workingDir must not exist")

        val compiler = BtaIncrementalCompiler.create(btaImplJars).getOrElse {
            fail("failed to load BTA toolchain: $it")
        }

        compiler.compile(
            IcRequest(
                projectId = "warm-lazy-mkdir",
                projectRoot = workRoot,
                sources = listOf(sourceFile),
                classpath = fixtureClasspath,
                outputDir = outputDir,
                workingDir = workingDir,
            ),
        ).getOrElse { fail("expected success, got Err($it)") }

        assertTrue(Files.isDirectory(workingDir), "adapter must mkdir workingDir on first compile")
    }

    @Test
    fun `cold compile populates workingDir with IC state and warm compile reuses it`() {
        val workRoot = Files.createTempDirectory("bta-warm-")
        val sourceFile = workRoot.resolve("Main.kt").also {
            it.writeText(
                """
                package fixture
                object Main {
                    fun greeting(): String = "hello"
                }
                """.trimIndent(),
            )
        }
        val outputDir = workRoot.resolve("classes").apply { createDirectories() }
        val workingDir = workRoot.resolve("ic-state")

        val compiler = BtaIncrementalCompiler.create(btaImplJars).getOrElse {
            fail("failed to load BTA toolchain: $it")
        }

        val request = IcRequest(
            projectId = "warm-path-smoke",
            projectRoot = workRoot,
            sources = listOf(sourceFile),
            classpath = fixtureClasspath,
            outputDir = outputDir,
            workingDir = workingDir,
        )

        // --- cold ---
        compiler.compile(request).getOrElse { fail("cold compile failed: $it") }

        val coldStateFileCount = workingDir.walk().filter { Files.isRegularFile(it) }.count()
        assertTrue(
            coldStateFileCount > 0,
            "BTA must write IC state under workingDir after cold compile (found $coldStateFileCount files)",
        )

        // --- touch ---
        // Append a uniquely-named top-level function so the resulting
        // .class bytes actually differ cold vs warm. A comment-only edit
        // would be byte-identical and defeat the intent of the test.
        val uniq = System.nanoTime()
        Files.writeString(
            sourceFile,
            "\nfun warmTouch$uniq(): Long = $uniq\n",
            StandardOpenOption.APPEND,
        )

        // --- warm ---
        compiler.compile(request).getOrElse { fail("warm compile failed: $it") }

        val classFiles = outputDir.walk().filter { it.extension == "class" }.toList()
        assertTrue(
            classFiles.any { it.fileName.toString() == "MainKt.class" || it.fileName.toString() == "Main.class" },
            "expected at least one fixture class file after warm compile: $classFiles",
        )

        // IC state on disk must survive the warm compile. If it were
        // wiped between requests, we would have a regression against the
        // "daemon-owned state across requests" invariant (ADR 0019 §5).
        val warmStateFileCount = workingDir.walk().filter { Files.isRegularFile(it) }.count()
        assertTrue(
            warmStateFileCount > 0,
            "IC state must still be present after warm compile (found $warmStateFileCount files)",
        )
    }

    private fun systemClasspath(key: String): List<Path> {
        val raw = System.getProperty(key)
            ?: error("$key system property not set — check :ic/build.gradle.kts test task config")
        return raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { Path.of(it) }
    }
}
