package kolt.build

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.infra.ProcessError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubprocessCompilerBackendArgvTest {

    private fun request(
        sources: List<String> = listOf("src"),
        classpath: List<String> = emptyList(),
        outputPath: String = "build/classes",
        moduleName: String = "my-app",
        extraArgs: List<String> = listOf("-jvm-target", "17"),
    ) = CompileRequest(
        workingDir = "",
        sources = sources,
        classpath = classpath,
        outputPath = outputPath,
        moduleName = moduleName,
        extraArgs = extraArgs,
    )

    @Test
    fun argvStartsWithKotlincBin() {
        val argv = subprocessArgv("/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc", request())
        assertEquals("/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc", argv.first())
    }

    @Test
    fun argvOmitsClasspathFlagWhenEmpty() {
        val argv = subprocessArgv("kotlinc", request(classpath = emptyList()))
        assertTrue("-cp" !in argv)
    }

    @Test
    fun argvIncludesClasspathJoinedByColon() {
        val argv = subprocessArgv(
            "kotlinc",
            request(classpath = listOf("/cache/a.jar", "/cache/b.jar")),
        )
        val cpIdx = argv.indexOf("-cp")
        assertTrue(cpIdx >= 0)
        assertEquals("/cache/a.jar:/cache/b.jar", argv[cpIdx + 1])
    }

    @Test
    fun argvEndsWithDFlagAndOutputPath() {
        val argv = subprocessArgv("kotlinc", request(outputPath = "build/classes"))
        val dIdx = argv.indexOf("-d")
        assertTrue(dIdx >= 0)
        assertEquals("build/classes", argv[dIdx + 1])
        assertEquals(argv.size - 2, dIdx)
    }

    @Test
    fun argvIncludesSourcesInOrder() {
        val argv = subprocessArgv(
            "kotlinc",
            request(sources = listOf("src", "generated")),
        )
        assertTrue(argv.containsAll(listOf("src", "generated")))
        assertTrue(argv.indexOf("src") < argv.indexOf("generated"))
    }

    @Test
    fun argvIncludesExtraArgs() {
        val argv = subprocessArgv(
            "kotlinc",
            request(extraArgs = listOf("-jvm-target", "21", "-Xplugin=foo.jar")),
        )
        assertTrue(argv.containsAll(listOf("-jvm-target", "21", "-Xplugin=foo.jar")))
    }

    // Cross-check: the new backend argv must match what the legacy Builder.buildCommand()
    // produces for the same logical inputs. This protects the S1 refactor from silently
    // changing the kotlinc invocation.
    @Test
    fun argvMatchesLegacyBuildCommandOutput() {
        val config = kolt.testConfig(jvmTarget = "17")
        val legacy = buildCommand(
            config,
            classpath = "/cache/a.jar:/cache/b.jar",
            pluginArgs = listOf("-Xplugin=foo.jar"),
            kotlincPath = "/managed/kotlinc",
        )
        val request = CompileRequest(
            workingDir = "",
            sources = config.sources,
            classpath = listOf("/cache/a.jar", "/cache/b.jar"),
            outputPath = CLASSES_DIR,
            moduleName = config.name,
            extraArgs = buildList {
                add("-jvm-target")
                add(config.jvmTarget)
                addAll(listOf("-Xplugin=foo.jar"))
            },
        )
        val argv = subprocessArgv("/managed/kotlinc", request)

        assertEquals(legacy.args, argv)
    }
}

class SubprocessCompilerBackendMapProcessErrorTest {

    @Test
    fun nonZeroExitMapsToCompilationFailedWithExitCode() {
        val mapped = mapProcessErrorToCompileError(ProcessError.NonZeroExit(42))
        val failure = assertIs<CompileError.CompilationFailed>(mapped)
        assertEquals(42, failure.exitCode)
    }

    @Test
    fun forkFailedMapsToBackendUnavailable() {
        val mapped = mapProcessErrorToCompileError(ProcessError.ForkFailed)
        assertIs<CompileError.BackendUnavailable>(mapped)
    }

    @Test
    fun waitFailedMapsToBackendUnavailable() {
        val mapped = mapProcessErrorToCompileError(ProcessError.WaitFailed)
        assertIs<CompileError.BackendUnavailable>(mapped)
    }

    @Test
    fun signalKilledMapsToBackendUnavailable() {
        val mapped = mapProcessErrorToCompileError(ProcessError.SignalKilled)
        assertIs<CompileError.BackendUnavailable>(mapped)
    }

    @Test
    fun popenFailedMapsToBackendUnavailable() {
        val mapped = mapProcessErrorToCompileError(ProcessError.PopenFailed)
        assertIs<CompileError.BackendUnavailable>(mapped)
    }

    @Test
    fun emptyArgsMapsToInternalMisuse() {
        val mapped = mapProcessErrorToCompileError(ProcessError.EmptyArgs)
        assertIs<CompileError.InternalMisuse>(mapped)
    }
}

class SubprocessCompilerBackendIntegrationTest {

    private val trivialRequest = CompileRequest(
        workingDir = "",
        sources = emptyList(),
        classpath = emptyList(),
        outputPath = "/tmp/kolt_subprocess_backend_test_out",
        moduleName = "test",
        extraArgs = emptyList(),
    )

    @Test
    fun compileReturnsOkWhenBinaryExitsZero() {
        // /bin/true ignores all args and exits 0. Exercises the success path of
        // executeCommand → CompileOutcome mapping without needing a real kotlinc.
        val backend = SubprocessCompilerBackend(kotlincBin = "/bin/true")
        val result = backend.compile(trivialRequest)
        val outcome = assertNotNull(result.get())
        assertEquals("", outcome.stdout)
        assertEquals("", outcome.stderr)
    }

    @Test
    fun compileReturnsCompilationFailedWhenBinaryExitsNonZero() {
        // /bin/false ignores all args and exits 1. This is how a real kotlinc
        // compile error surfaces at the process layer.
        val backend = SubprocessCompilerBackend(kotlincBin = "/bin/false")
        val result = backend.compile(trivialRequest)
        val error = assertNotNull(result.getError())
        val failure = assertIs<CompileError.CompilationFailed>(error)
        assertEquals(1, failure.exitCode)
    }
}
