package kolt.cli

import kolt.build.nativeTestLibraryCommand
import kolt.build.nativeTestLinkCommand
import kolt.build.nativeTestRunCommand
import kolt.build.outputNativeTestKexePath
import kolt.build.outputNativeTestKlibPath
import kolt.build.testBuildCommand
import kolt.build.testRunCommand
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R5 matrix: `kolt test` non-regression for libraries (design.md §Components
 * and Interfaces → Test non-regression; design.md §Testing Strategy →
 * Non-regression — `kolt test` (R5)).
 *
 * The test pipeline is already decoupled from `config.build.main` in the
 * gap-analysis verified paths:
 *
 * - JVM: `doTest` (BuildCommands.kt:501) → `testBuildCommand` /
 *   `testRunCommand` — neither reads `config.build.main`.
 * - Native: `doNativeTest` (BuildCommands.kt:556) → `nativeTestLibraryCommand`
 *   / `nativeTestLinkCommand` / `nativeTestRunCommand` — the link step
 *   uses `-generate-test-runner`, never `-e <FQN>`.
 *
 * This file locks that invariant: test-pipeline helpers must not surface
 * `config.build.main` in their emitted argv for either kind. Construction
 * of the commands succeeds for a `kind = "lib"` config with `main = null`
 * (R5.1, R5.2), and for a `kind = "app"` config with a poisoned FQN the
 * FQN is absent from every test-path argv (R5.3).
 *
 * The end-to-end "exit 0 + tests execute" check requires a live JDK +
 * kotlinc/konanc toolchain and is covered by task 6.1's dogfood gate. The
 * invariant tested here — that no test-path code reads `build.main` — is
 * the structural contract R5.2 pins.
 */
class TestLibraryNonRegressionTest {

    // R5.1 / R5.2: the JVM test-compile argv for a `kind = "lib"` config
    // with `main = null` is well-formed and carries no `-e` entry-point
    // flag and no `Main-Class` / `-include-runtime` affordance. Command
    // construction succeeds without reading `build.main`.
    @Test
    fun jvmLibraryTestBuildCommandIsConstructedWithoutReadingMain() {
        val base = testConfig(name = "mylib", target = "jvm")
        val libConfig = base.copy(kind = "lib", build = base.build.copy(main = null))

        val cmd = testBuildCommand(libConfig, classesDir = "build/classes")

        assertFalse(
            cmd.args.contains("-e"),
            "JVM lib test-compile must not carry an entry-point flag: ${cmd.args}",
        )
        assertFalse(
            cmd.args.contains("-include-runtime"),
            "JVM lib test-compile must stay thin (no -include-runtime): ${cmd.args}",
        )
        assertTrue(
            cmd.args.containsAll(libConfig.build.testSources),
            "JVM lib test-compile must reference every test source dir: ${cmd.args}",
        )
        assertEquals("build/test-classes", cmd.outputPath)
    }

    // R5.1 / R5.2: the JVM test-run argv is a junit-console-launcher
    // invocation; neither the classpath nor the `java` argv carries the
    // project's `main` FQN. `testRunCommand` takes no `main` parameter at
    // all, so the R5.2 structural contract is enforced by construction.
    @Test
    fun jvmLibraryTestRunCommandDoesNotReferenceMainFqn() {
        val base = testConfig(name = "mylib", target = "jvm")
        val libConfig = base.copy(kind = "lib", build = base.build.copy(main = null))

        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/fake/junit-console-launcher.jar",
        )

        assertFalse(
            cmd.args.contains("-e"),
            "JVM test-run must not carry an entry-point flag: ${cmd.args}",
        )
        assertFalse(
            cmd.args.any { it.endsWith("MainKt") },
            "JVM test-run must not invoke the project Main-Class facade: ${cmd.args}",
        )
        assertTrue(
            cmd.args.contains("--scan-class-path"),
            "JVM test-run must use the junit-console scan-class-path discovery: ${cmd.args}",
        )
        // Double-check: the lib config carries no main; just assert the
        // argv is constructible and references the test classes dir.
        assertTrue(
            cmd.args.any { it.contains("build/test-classes") },
            "JVM test-run classpath must include the test-classes dir: ${cmd.args}",
        )
        assertEquals(null, libConfig.build.main)
    }

    // R5.1 / R5.2: the native test stage-1 (library) argv for a
    // `kind = "lib"` config with `main = null` compiles the main + test
    // sources without any entry-point reference. The command emits the
    // canonical `-p library -nopack` shape used by ADR 0013.
    @Test
    fun nativeLibraryTestLibraryCommandIsConstructedWithoutReadingMain() {
        val base = testConfig(name = "mylib", target = "linuxX64")
        val libConfig = base.copy(kind = "lib", build = base.build.copy(main = null))

        val cmd = nativeTestLibraryCommand(libConfig)

        assertFalse(
            cmd.args.contains("-e"),
            "native lib test stage-1 must not carry an entry-point flag: ${cmd.args}",
        )
        assertTrue(
            cmd.args.contains("library"),
            "native lib test stage-1 must compile with `-p library`: ${cmd.args}",
        )
        assertTrue(
            cmd.args.contains("-nopack"),
            "native lib test stage-1 must preserve the `-nopack` convention: ${cmd.args}",
        )
        assertEquals(outputNativeTestKlibPath(libConfig), cmd.outputPath)
    }

    // R5.1 / R5.2: the native test-link argv for a `kind = "lib"` config
    // uses `-generate-test-runner` (ADR 0013) — NOT `-e <FQN>`. This is
    // the load-bearing structural guarantee that `doNativeTest` does not
    // require `config.build.main`. A single `-e` appearance here would
    // flag a regression that re-coupled the test path to `main`.
    @Test
    fun nativeLibraryTestLinkCommandUsesGeneratedTestRunnerAndNeverE() {
        val base = testConfig(name = "mylib", target = "linuxX64")
        val libConfig = base.copy(kind = "lib", build = base.build.copy(main = null))

        val cmd = nativeTestLinkCommand(libConfig)

        assertFalse(
            cmd.args.contains("-e"),
            "native lib test-link must not carry an entry-point flag: ${cmd.args}",
        )
        assertTrue(
            cmd.args.contains("-generate-test-runner"),
            "native lib test-link must use the generated test runner: ${cmd.args}",
        )
        assertEquals(outputNativeTestKexePath(libConfig), cmd.outputPath)
    }

    // R5.1 / R5.2: the native test-run argv is the path of the test-kexe
    // followed by forwarded args. `nativeTestRunCommand` takes no `main`
    // parameter and reads only `config.name` from the config, so the
    // structural contract is enforced by construction.
    @Test
    fun nativeLibraryTestRunCommandInvokesTestKexeByPath() {
        val base = testConfig(name = "mylib", target = "linuxX64")
        val libConfig = base.copy(kind = "lib", build = base.build.copy(main = null))

        val cmd = nativeTestRunCommand(libConfig)

        assertEquals(outputNativeTestKexePath(libConfig), cmd.args.first())
        assertFalse(
            cmd.args.contains("-e"),
            "native lib test-run must not carry an entry-point flag: ${cmd.args}",
        )
    }

    // R5.3: for a `kind = "app"` config, the test-pipeline argv must not
    // reference the app's `main` FQN anywhere. Using a poisoned FQN
    // (`com.example.poisonedMain`) turns any accidental read of
    // `config.build.main` into a substring match that this test would
    // catch. This is the app-side companion to R5.2 — the test pipeline
    // must stay decoupled from `main` regardless of kind.
    @Test
    fun applicationTestPipelineDoesNotLeakMainFqnIntoAnyArgv() {
        val poisonFqn = "com.example.poisonedMain"
        val base = testConfig(name = "myapp", target = "jvm")
        val appConfig = base.copy(
            kind = "app",
            build = base.build.copy(main = poisonFqn),
        )

        val jvmBuild = testBuildCommand(appConfig, classesDir = "build/classes")
        val jvmRun = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/fake/junit-console-launcher.jar",
        )

        assertFalse(
            jvmBuild.args.any { it.contains(poisonFqn) },
            "JVM app test-compile must not reference the `main` FQN: ${jvmBuild.args}",
        )
        assertFalse(
            jvmRun.args.any { it.contains(poisonFqn) },
            "JVM app test-run must not reference the `main` FQN: ${jvmRun.args}",
        )
    }

    // R5.3: same poisoned-FQN guard for the native test-pipeline helpers.
    // `-generate-test-runner` is what decouples ADR 0013's native test
    // flow from the app entry point; this test pins it.
    @Test
    fun applicationNativeTestPipelineDoesNotLeakMainFqnIntoAnyArgv() {
        val poisonFqn = "com.example.poisonedMain"
        val base = testConfig(name = "myapp", target = "linuxX64")
        val appConfig = base.copy(
            kind = "app",
            build = base.build.copy(main = poisonFqn),
        )

        val library = nativeTestLibraryCommand(appConfig)
        val link = nativeTestLinkCommand(appConfig)
        val run = nativeTestRunCommand(appConfig)

        for ((label, args) in listOf(
            "stage-1 library" to library.args,
            "test-link" to link.args,
            "test-run" to run.args,
        )) {
            assertFalse(
                args.any { it.contains(poisonFqn) },
                "native app $label must not reference the `main` FQN: $args",
            )
            assertFalse(
                args.contains("-e"),
                "native app $label must not carry an entry-point flag: $args",
            )
        }
        assertTrue(
            link.args.contains("-generate-test-runner"),
            "native app test-link must use the generated test runner: ${link.args}",
        )
    }
}
