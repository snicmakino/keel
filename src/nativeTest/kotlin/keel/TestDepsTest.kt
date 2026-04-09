package keel

import kotlin.test.Test
import kotlin.test.assertEquals

class TestDepsTest {

    @Test
    fun jvmTargetInjectsKotlinTestJunit5() {
        val config = testConfig()
        val injected = autoInjectedTestDeps(config)

        assertEquals(
            mapOf("org.jetbrains.kotlin:kotlin-test-junit5" to "2.1.0"),
            injected
        )
    }

    @Test
    fun nativeTargetInjectsNothing() {
        val config = KeelConfig(
            name = "my-app", version = "0.1.0", kotlin = "2.1.0",
            target = "native", main = "MainKt", sources = listOf("src")
        )
        val injected = autoInjectedTestDeps(config)

        assertEquals(emptyMap(), injected)
    }

    @Test
    fun kotlinVersionMatchesConfig() {
        val config = KeelConfig(
            name = "my-app", version = "0.1.0", kotlin = "2.2.0",
            target = "jvm", main = "MainKt", sources = listOf("src")
        )
        val injected = autoInjectedTestDeps(config)

        assertEquals("2.2.0", injected["org.jetbrains.kotlin:kotlin-test-junit5"])
    }

    @Test
    fun userTestDepOverridesAutoInjected() {
        val config = testConfig(testDependencies = mapOf(
            "org.jetbrains.kotlin:kotlin-test-junit5" to "2.0.0"
        ))
        val injected = autoInjectedTestDeps(config)
        val allDeps = injected + config.dependencies + config.testDependencies

        // User's explicit version wins (Map.plus keeps right-hand side)
        assertEquals("2.0.0", allDeps["org.jetbrains.kotlin:kotlin-test-junit5"])
    }

    @Test
    fun mergedDepsIncludesAutoInjectedAndUserDeps() {
        val config = testConfig(testDependencies = mapOf(
            "io.kotest:kotest-runner-junit5" to "5.8.0"
        ))
        val injected = autoInjectedTestDeps(config)
        val allTestDeps = injected + config.testDependencies

        assertEquals(2, allTestDeps.size)
        assertEquals("2.1.0", allTestDeps["org.jetbrains.kotlin:kotlin-test-junit5"])
        assertEquals("5.8.0", allTestDeps["io.kotest:kotest-runner-junit5"])
    }
}
