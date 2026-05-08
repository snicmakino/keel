package kolt.config

import com.github.michaelbull.result.getError
import kolt.infra.output.Severity
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderConfigErrorTest {

  @Test
  fun syntaxErrorIncludesLineNumberAndPath() {
    val toml = "name = \"unclosed-string\nversion = \"0.1\"\n"
    val err =
      assertIs<ConfigError.ParseFailed>(parseConfig(toml, path = "/abs/kolt.toml").getError())
    val diag = renderConfigError(err)
    assertEquals(Severity.Error, diag.severity)
    val first = diag.context.first()
    assertTrue(first.contains("/abs/kolt.toml"), "missing path in: $first")
    // Either path-only or path:lineNo, but lineNo should be there for syntax errors.
    val lineNo = err.lineNo
    assertNotNull(lineNo, "ktoml usually carries lineNo for syntax errors")
    assertTrue(first.contains(":$lineNo"), "missing lineNo in: $first")
  }

  @Test
  fun unknownTopLevelKeySurfacesSuggestion() {
    // Unknown top-level scalar with a name close to a known section.
    val toml =
      """
            name = "x"
            version = "0.1"
            koltn = "stray"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            sources = ["src"]
            main = "com.example.main"
        """
        .trimIndent()
    val err =
      assertIs<ConfigError.ParseFailed>(parseConfig(toml, path = "/abs/kolt.toml").getError())
    assertEquals("koltn", err.keyPath)
    assertEquals("kotlin", err.suggestion)
    val diag = renderConfigError(err)
    assertContains(diag.context, "key: koltn")
    assertEquals("Did you mean `kotlin`?", diag.hint)
  }

  @Test
  fun unknownNestedKeyOmitsSuggestion() {
    val toml =
      """
            name = "x"
            version = "0.1"
            [kotlin]
            version = "2.1.0"
            compilerr = "wrong"

            [build]
            target = "jvm"
            sources = ["src"]
            main = "com.example.main"
        """
        .trimIndent()
    val err =
      assertIs<ConfigError.ParseFailed>(parseConfig(toml, path = "/abs/kolt.toml").getError())
    assertEquals("compilerr", err.keyPath, "expected nested unknown key path")
    assertNull(err.suggestion, "nested unknowns must not surface a Did-you-mean")
    val diag = renderConfigError(err)
    assertContains(diag.context, "key: compilerr")
    assertNull(diag.hint)
  }

  @Test
  fun nullPathOmitsLocationLine() {
    val err = ConfigError.ParseFailed("manual error", path = null, lineNo = null)
    val diag = renderConfigError(err)
    assertEquals(emptyList(), diag.context)
    assertNull(diag.hint)
  }

  @Test
  fun pathWithoutLineNoStillRendersLocation() {
    val err = ConfigError.ParseFailed("manual error", path = "/abs/kolt.toml", lineNo = null)
    val diag = renderConfigError(err)
    assertContains(diag.context, "at /abs/kolt.toml")
  }
}
