package kolt.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.exceptions.TomlDecodingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.serialization.Serializable

class ParseUnknownKeyTest {
  @Test
  fun topLevelTypoYieldsKeyAndSuggestion() {
    // ktoml 0.7.1 names the root TOML node "rootNode" — top-level typos
    // surface with `in scope <rootNode>`.
    val (key, suggestion) =
      parseUnknownKey(
        "Unknown key received: <koltn> in scope <rootNode>. Switch the configuration option"
      )
    assertEquals("koltn", key)
    assertEquals("kotlin", suggestion)
  }

  @Test
  fun nestedUnknownKeyYieldsKeyButNoSuggestion() {
    // Did-you-mean for nested keys is explicitly out of scope (R3.4 narrowed).
    val (key, suggestion) =
      parseUnknownKey(
        "Unknown key received: <compilerr> in scope <kotlin>. Switch the configuration option"
      )
    assertEquals("compilerr", key)
    assertNull(suggestion)
  }

  @Test
  fun unrelatedMessageYieldsBothNull() {
    val (key, suggestion) = parseUnknownKey("syntax error somewhere")
    assertNull(key)
    assertNull(suggestion)
  }

  @Test
  fun topLevelKeyTooFarFromAnyKnownYieldsNoSuggestion() {
    // "totallyunrelated" length 16 → adaptive threshold 2; no candidate
    // within distance 2 of any KNOWN_TOP_LEVEL_SECTIONS entry.
    val (key, suggestion) =
      parseUnknownKey(
        "Unknown key received: <totallyunrelated> in scope <rootNode>. Switch the configuration option"
      )
    assertEquals("totallyunrelated", key)
    assertNull(suggestion)
  }

  @Test
  fun emptyScopeIsTreatedAsNestedSinceKtomlEmitsRootNode() {
    // Defensive: should ktoml ever emit `<>` as a scope, that is NOT the
    // top-level root marker (real ktoml root is "rootNode"); the helper
    // must not silently treat it as top-level.
    val (key, suggestion) = parseUnknownKey("Unknown key received: <koltn> in scope <>")
    assertEquals("koltn", key)
    assertNull(suggestion)
  }

  @Test
  fun knownTopLevelSectionsListIsAlphabetical() {
    val sorted = KNOWN_TOP_LEVEL_SECTIONS.sorted()
    assertEquals(sorted, KNOWN_TOP_LEVEL_SECTIONS)
  }

  @Test
  fun knownTopLevelSectionsCoverAllRawConfigFields() {
    // Drift guard: every section name a user might type at the top level
    // (matched by the field-name / @SerialName of RawKoltConfig) is listed.
    // Scalar fields (name / version / kind / package) are intentionally
    // excluded — typos in those would surface as parse errors, not
    // `[unknown]` section warnings, and offering Did-you-mean would mislead.
    val expected =
      setOf(
        "build",
        "cinterop",
        "classpaths",
        "dependencies",
        "fmt",
        "kotlin",
        "repositories",
        "run",
        "test",
        "test-dependencies",
        "tools",
      )
    assertEquals(expected, KNOWN_TOP_LEVEL_SECTIONS.toSet())
  }

  // Empirical pin: invoke real ktoml 0.7.1 with strict-unknown-keys mode
  // against a synthetic broken kolt.toml and assert the message format
  // our regex assumes. This catches ktoml format drift on bumps.
  @Serializable private data class TinySchema(val name: String = "")

  @Test
  fun ktomlEmitsExpectedUnknownKeyFormatForTopLevel() {
    val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))
    val raw = "[koltn]\nfoo = \"bar\"\n"
    try {
      toml.decodeFromString(TinySchema.serializer(), raw)
      fail("expected TomlDecodingException")
    } catch (e: TomlDecodingException) {
      val message = e.message ?: fail("ktoml exception had null message")
      val (key, suggestion) = parseUnknownKey(extractKtomlLineNo(message).second)
      assertEquals("koltn", key, "actual ktoml message: $message")
      assertEquals("kotlin", suggestion, "actual ktoml message: $message")
    }
  }
}
