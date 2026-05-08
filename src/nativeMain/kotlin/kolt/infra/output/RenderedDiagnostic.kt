package kolt.infra.output

data class RenderedDiagnostic(
  val severity: Severity,
  val headline: String,
  val context: List<String> = emptyList(),
  val hint: String? = null,
)
