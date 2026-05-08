package kolt.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.exceptions.TomlDecodingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlinx.serialization.Serializable

// Pins the ktoml-core 0.7.1 exception-message format that
// `KtomlMessageParse.LINE_NO_REGEX` and the unknown-key regex rely on.
// A ktoml major bump that reshapes either prefix surfaces here as a RED
// test — that is the cue to revisit the regex / scope marker
// (`KTOML_ROOT_SCOPE`) before line-number / Did-you-mean rendering
// silently breaks in production.
class ConfigParseMessageFormatTest {

  @Serializable private data class TinyConfig(val name: String = "", val version: String = "")

  private val strictToml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))

  private fun decodeExpectingFailure(raw: String): String {
    try {
      strictToml.decodeFromString(TinyConfig.serializer(), raw)
      fail("expected TomlDecodingException for input: $raw")
    } catch (e: TomlDecodingException) {
      return assertNotNull(e.message, "ktoml exception had null message")
    }
  }

  @Test
  fun syntaxErrorMessageStartsWithLinePrefix() {
    // Unterminated string — ktoml flags the line.
    val message = decodeExpectingFailure("name = \"unterminated\nversion = \"0.1\"\n")
    val matched = LINE_NO_REGEX.find(message)
    assertNotNull(matched, "ktoml syntax error did not match `^Line N: ` prefix; actual: $message")
  }

  @Test
  fun unknownTopLevelKeyMessageMatchesUnknownKeyRegex() {
    val raw = "name = \"x\"\nversion = \"0.1\"\nkoltn = \"stray\"\n"
    val message = decodeExpectingFailure(raw)
    val regex = Regex("Unknown key received: <([^>]+)> in scope <([^>]*)>")
    val match = regex.find(message)
    assertNotNull(
      match,
      "ktoml unknown-top-level message did not match expected format; actual: $message",
    )
    val (key, scope) = match.destructured
    assertEquals("koltn", key, "full ktoml message: $message")
    assertEquals("rootNode", scope, "expected TomlFile.name; full ktoml message: $message")
  }

  @Test
  fun unknownNestedKeyMessageMatchesAndCarriesParentScope() {
    @Serializable data class Inner(val a: String = "")
    @Serializable data class Outer(val name: String = "", val nested: Inner = Inner())

    val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))
    val raw = "name = \"x\"\n[nested]\na = \"ok\"\nstray = \"unknown\"\n"
    val message =
      try {
        toml.decodeFromString(Outer.serializer(), raw)
        fail("expected TomlDecodingException")
      } catch (e: TomlDecodingException) {
        assertNotNull(e.message, "ktoml exception had null message")
      }
    val regex = Regex("Unknown key received: <([^>]+)> in scope <([^>]*)>")
    val match = regex.find(message)
    assertNotNull(
      match,
      "ktoml unknown-nested-key message did not match expected format; actual: $message",
    )
    val (key, scope) = match.destructured
    assertEquals("stray", key, "full ktoml message: $message")
    assertEquals("nested", scope, "full ktoml message: $message")
  }
}
