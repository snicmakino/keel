package kolt.usertool

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.resolve.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolSectionParseTest {

  @Test
  fun parsesGroupArtifactVersion() {
    val result = parseCoordsString("a.b:c:1.0")
    val pair = assertNotNull(result.get())
    assertEquals(Coordinate("a.b", "c", "1.0"), pair.first)
    assertNull(pair.second)
  }

  @Test
  fun parsesGroupArtifactVersionClassifier() {
    val result = parseCoordsString("a.b:c:1.0:cls")
    val pair = assertNotNull(result.get())
    assertEquals(Coordinate("a.b", "c", "1.0"), pair.first)
    assertEquals("cls", pair.second)
  }

  @Test
  fun parsesRealisticKtlintCoords() {
    val result = parseCoordsString("com.pinterest.ktlint:ktlint-cli:1.3.1:all")
    val pair = assertNotNull(result.get())
    assertEquals(Coordinate("com.pinterest.ktlint", "ktlint-cli", "1.3.1"), pair.first)
    assertEquals("all", pair.second)
  }

  @Test
  fun rejectsMissingVersion() {
    val err = parseCoordsString("a.b:c").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsEmptyString() {
    val err = parseCoordsString("").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsMissingGroup() {
    val err = parseCoordsString(":c:1.0").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsMissingArtifact() {
    val err = parseCoordsString("a.b::1.0").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsEmptyVersion() {
    val err = parseCoordsString("a.b:c:").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsTooManyColons() {
    val err = parseCoordsString("a:b:1.0:cls:extra").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsInvalidGroupCharset() {
    val err = parseCoordsString("a/b:c:1.0").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsInvalidArtifactCharset() {
    val err = parseCoordsString("a.b:c d:1.0").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsInvalidClassifierCharset() {
    val err = parseCoordsString("a.b:c:1.0:cl s").getError()
    assertNotNull(err)
  }

  @Test
  fun acceptsAllowedCharsetsInGroupArtifactVersionClassifier() {
    val result = parseCoordsString("g_1.A-x:art-2_b.x:1.0-RC_1:cl-s_2.x")
    val pair = assertNotNull(result.get())
    assertEquals(Coordinate("g_1.A-x", "art-2_b.x", "1.0-RC_1"), pair.first)
    assertEquals("cl-s_2.x", pair.second)
  }

  @Test
  fun rejectsEmptyClassifier() {
    // Trailing colon without classifier means malformed (4 parts but classifier empty).
    val err = parseCoordsString("a.b:c:1.0:").getError()
    assertNotNull(err)
  }

  @Test
  fun errorMessageMentionsInput() {
    val err = parseCoordsString("not-coords").getError()
    assertNotNull(err)
    assertTrue(err.isNotEmpty(), "error message should be non-empty")
  }
}
