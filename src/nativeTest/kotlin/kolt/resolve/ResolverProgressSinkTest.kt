package kolt.resolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverProgressSinkTest {

  @Test
  fun noOpSinkSwallowsCallbacks() {
    val sink = ResolverProgressSink.NoOp
    sink.onArtifactStart(1, 3, "com.example:lib", "1.0.0")
    sink.onRetryAgainst("https://repo.example.com/maven2/")
  }

  @Test
  fun recordingSinkCapturesCallbacksInOrder() {
    val sink = RecordingResolverProgressSink()

    sink.onArtifactStart(1, 3, "com.example:a", "1.0.0")
    sink.onArtifactStart(2, 3, "com.example:b", "2.0.0")
    sink.onRetryAgainst("https://repo.internal.example.com/maven2/")
    sink.onArtifactStart(3, 3, "com.example:c", "3.0.0")

    val expected =
      listOf<RecordedProgressEvent>(
        RecordedProgressEvent.ArtifactStart(1, 3, "com.example:a", "1.0.0"),
        RecordedProgressEvent.ArtifactStart(2, 3, "com.example:b", "2.0.0"),
        RecordedProgressEvent.RetryAgainst("https://repo.internal.example.com/maven2/"),
        RecordedProgressEvent.ArtifactStart(3, 3, "com.example:c", "3.0.0"),
      )
    assertEquals(expected, sink.events)
  }

  @Test
  fun recordingSinkEventsViewIsImmutableSnapshot() {
    val sink = RecordingResolverProgressSink()
    sink.onArtifactStart(1, 1, "com.example:lib", "1.0.0")
    val snapshot = sink.events
    sink.onRetryAgainst("https://repo.example.com/maven2/")

    assertEquals(1, snapshot.size)
    assertEquals(2, sink.events.size)
    assertTrue(snapshot[0] is RecordedProgressEvent.ArtifactStart)
  }
}
