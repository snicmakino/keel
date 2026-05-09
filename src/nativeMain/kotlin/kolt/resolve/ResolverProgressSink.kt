package kolt.resolve

interface ResolverProgressSink {
  fun onArtifactStart(index: Int, total: Int, groupArtifact: String, version: String)

  fun onRetryAgainst(repository: String)

  companion object {
    val NoOp: ResolverProgressSink =
      object : ResolverProgressSink {
        override fun onArtifactStart(
          index: Int,
          total: Int,
          groupArtifact: String,
          version: String,
        ) {}

        override fun onRetryAgainst(repository: String) {}
      }
  }
}
