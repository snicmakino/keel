package kolt.resolve

sealed class RecordedProgressEvent {
  data class ArtifactStart(
    val index: Int,
    val total: Int,
    val groupArtifact: String,
    val version: String,
  ) : RecordedProgressEvent()

  data class RetryAgainst(val repository: String) : RecordedProgressEvent()
}

class RecordingResolverProgressSink : ResolverProgressSink {
  private val recorded = mutableListOf<RecordedProgressEvent>()

  val events: List<RecordedProgressEvent>
    get() = recorded.toList()

  override fun onArtifactStart(index: Int, total: Int, groupArtifact: String, version: String) {
    recorded += RecordedProgressEvent.ArtifactStart(index, total, groupArtifact, version)
  }

  override fun onRetryAgainst(repository: String) {
    recorded += RecordedProgressEvent.RetryAgainst(repository)
  }
}
