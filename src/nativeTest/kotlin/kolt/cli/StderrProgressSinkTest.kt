package kolt.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class StderrProgressSinkTest {
  @Test
  fun stderrProgressSinkFormatsArtifactStartAndRetryAgainst() {
    val captured = mutableListOf<String>()
    val sink = newStderrProgressSinkForTest { captured.add(it) }
    sink.onArtifactStart(2, 5, "com.example:lib", "1.0.0")
    sink.onRetryAgainst("https://repo.example.com/maven2/")
    assertEquals(
      listOf("[2/5] com.example:lib:1.0.0", "  -> retry against https://repo.example.com/maven2/"),
      captured,
    )
  }
}
