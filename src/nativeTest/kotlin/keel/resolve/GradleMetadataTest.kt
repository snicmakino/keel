package keel.resolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GradleMetadataTest {

    @Test
    fun parseJvmRedirectFromOkhttpModule() {
        val json = """
        {
          "formatVersion": "1.1",
          "component": {
            "group": "com.squareup.okhttp3",
            "module": "okhttp",
            "version": "5.3.2"
          },
          "variants": [
            {
              "name": "metadataApiElements",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "common"
              }
            },
            {
              "name": "jvmApiElements-published",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "available-at": {
                "url": "../../okhttp-jvm/5.3.2/okhttp-jvm-5.3.2.module",
                "group": "com.squareup.okhttp3",
                "module": "okhttp-jvm",
                "version": "5.3.2"
              }
            }
          ]
        }
        """.trimIndent()

        val redirect = parseJvmRedirect(json)

        assertEquals("com.squareup.okhttp3", redirect?.group)
        assertEquals("okhttp-jvm", redirect?.module)
        assertEquals("5.3.2", redirect?.version)
    }

    @Test
    fun parseJvmRedirectReturnsNullForNonKmpLibrary() {
        val json = """
        {
          "formatVersion": "1.1",
          "component": {
            "group": "com.google.guava",
            "module": "guava",
            "version": "33.0.0-jre"
          },
          "variants": [
            {
              "name": "apiElements",
              "attributes": {
                "org.gradle.usage": "java-api"
              },
              "dependencies": [
                {
                  "group": "com.google.code.findbugs",
                  "module": "jsr305",
                  "version": { "requires": "3.0.2" }
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val redirect = parseJvmRedirect(json)

        assertNull(redirect)
    }

    @Test
    fun parseJvmRedirectReturnsNullForInvalidJson() {
        val redirect = parseJvmRedirect("not json")
        assertNull(redirect)
    }

    @Test
    fun parseJvmRedirectReturnsNullForEmptyVariants() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": []
        }
        """.trimIndent()

        val redirect = parseJvmRedirect(json)

        assertNull(redirect)
    }

    @Test
    fun parseJvmRedirectSkipsJvmVariantWithoutAvailableAt() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "jvmApiElements-published",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "dependencies": [
                {
                  "group": "com.example",
                  "module": "lib",
                  "version": { "requires": "1.0" }
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val redirect = parseJvmRedirect(json)

        assertNull(redirect)
    }

    @Test
    fun parseJvmRedirectPicksJvmRuntimeVariantToo() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "jvmRuntimeElements-published",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "available-at": {
                "url": "../../lib-jvm/1.0/lib-jvm-1.0.module",
                "group": "com.example",
                "module": "lib-jvm",
                "version": "1.0"
              }
            }
          ]
        }
        """.trimIndent()

        val redirect = parseJvmRedirect(json)

        assertEquals("com.example", redirect?.group)
        assertEquals("lib-jvm", redirect?.module)
        assertEquals("1.0", redirect?.version)
    }
}
