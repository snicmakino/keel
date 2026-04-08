package keel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigTest {

    @Test
    fun parseMinimalConfig() {
        val json = """
        {
            "name": "my-app",
            "version": "0.1.0",
            "kotlin": "2.1.0",
            "target": "jvm",
            "main": "com.example.MainKt",
            "sources": ["src"]
        }
        """.trimIndent()

        val config = parseConfig(json)

        assertEquals("my-app", config.name)
        assertEquals("0.1.0", config.version)
        assertEquals("2.1.0", config.kotlin)
        assertEquals("jvm", config.target)
        assertEquals("com.example.MainKt", config.main)
        assertEquals(listOf("src"), config.sources)
        assertEquals("17", config.jvmTarget)
        assertEquals(emptyMap(), config.dependencies)
    }

    @Test
    fun parseConfigWithExplicitJvmTarget() {
        val json = """
        {
            "name": "my-app",
            "version": "0.1.0",
            "kotlin": "2.1.0",
            "target": "jvm",
            "jvm_target": "21",
            "main": "com.example.MainKt",
            "sources": ["src"]
        }
        """.trimIndent()

        val config = parseConfig(json)
        assertEquals("21", config.jvmTarget)
    }

    @Test
    fun parseConfigWithDependencies() {
        val json = """
        {
            "name": "my-app",
            "version": "0.1.0",
            "kotlin": "2.1.0",
            "target": "jvm",
            "main": "com.example.MainKt",
            "sources": ["src"],
            "dependencies": {
                "org.jetbrains.kotlinx:kotlinx-coroutines-core": "1.9.0",
                "com.squareup.okhttp3:okhttp": "4.12.0"
            }
        }
        """.trimIndent()

        val config = parseConfig(json)
        assertEquals(2, config.dependencies.size)
        assertEquals("1.9.0", config.dependencies["org.jetbrains.kotlinx:kotlinx-coroutines-core"])
        assertEquals("4.12.0", config.dependencies["com.squareup.okhttp3:okhttp"])
    }

    @Test
    fun parseConfigWithMultipleSources() {
        val json = """
        {
            "name": "my-app",
            "version": "0.1.0",
            "kotlin": "2.1.0",
            "target": "jvm",
            "main": "com.example.MainKt",
            "sources": ["src", "generated"]
        }
        """.trimIndent()

        val config = parseConfig(json)
        assertEquals(listOf("src", "generated"), config.sources)
    }

    @Test
    fun missingRequiredFieldThrows() {
        val json = """
        {
            "version": "0.1.0",
            "kotlin": "2.1.0",
            "target": "jvm",
            "main": "com.example.MainKt",
            "sources": ["src"]
        }
        """.trimIndent()

        assertFailsWith<ConfigParseException> {
            parseConfig(json)
        }
    }

    @Test
    fun invalidJsonThrows() {
        assertFailsWith<ConfigParseException> {
            parseConfig("not json")
        }
    }

    @Test
    fun emptySourcesArray() {
        val json = """
        {
            "name": "my-app",
            "version": "0.1.0",
            "kotlin": "2.1.0",
            "target": "jvm",
            "main": "com.example.MainKt",
            "sources": []
        }
        """.trimIndent()

        val config = parseConfig(json)
        assertEquals(emptyList(), config.sources)
    }

    @Test
    fun wrongFieldTypeThrows() {
        val json = """
        {
            "name": 123,
            "version": "0.1.0",
            "kotlin": "2.1.0",
            "target": "jvm",
            "main": "com.example.MainKt",
            "sources": ["src"]
        }
        """.trimIndent()

        assertFailsWith<ConfigParseException> {
            parseConfig(json)
        }
    }

    @Test
    fun jsonArrayRootThrows() {
        assertFailsWith<ConfigParseException> {
            parseConfig("[1, 2, 3]")
        }
    }

    @Test
    fun wrongDependencyValueTypeThrows() {
        val json = """
        {
            "name": "my-app",
            "version": "0.1.0",
            "kotlin": "2.1.0",
            "target": "jvm",
            "main": "com.example.MainKt",
            "sources": ["src"],
            "dependencies": {
                "org.example:lib": 123
            }
        }
        """.trimIndent()

        assertFailsWith<ConfigParseException> {
            parseConfig(json)
        }
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val json = """
        {
            "name": "my-app",
            "version": "0.1.0",
            "kotlin": "2.1.0",
            "target": "jvm",
            "main": "com.example.MainKt",
            "sources": ["src"],
            "unknown_field": "value"
        }
        """.trimIndent()

        val config = parseConfig(json)
        assertEquals("my-app", config.name)
    }
}
