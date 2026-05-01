package kolt.config

/**
 * Classification of how the watch loop should react when a kolt.toml section changes.
 *
 * The taxonomy is fixed at three top-level values per ADR 0033. AutoReload carries a sub-attribute
 * distinguishing rebuild-required from rebuild-not-required; NotifyOnly carries a per-section
 * user-recommended action string.
 */
sealed class SectionAction {
  data class AutoReload(val rebuild: Boolean) : SectionAction()

  data class NotifyOnly(val recommendation: String) : SectionAction()

  data object NoOp : SectionAction()
}

/** A single section-level change detected between two KoltConfig snapshots. */
data class SectionChange(val sectionName: String, val action: SectionAction)

/**
 * Aggregated dispatch decision for one debounce window.
 *
 * Invariant (enforced by planDispatch): notifications.isNotEmpty() iff (!reload && !rebuild). This
 * expresses the notify-only-prevail rule for mixed windows.
 */
data class DispatchPlan(
  val reload: Boolean,
  val rebuild: Boolean,
  val notifications: List<String>,
  val changedSections: List<SectionChange>,
)

/** Marker that prefixes every kolt.toml change-handling notification line on stderr. */
const val NOTIFICATION_MARKER: String = "[watch] ⚠"

/**
 * Classify the section-level diff between two KoltConfig snapshots.
 *
 * Stub: returns empty list.
 */
fun classifyChange(old: KoltConfig, new: KoltConfig): List<SectionChange> {
  return emptyList()
}

/**
 * Decide reload / rebuild / notification dispatch for a list of section changes.
 *
 * Stub: returns an empty plan.
 */
fun planDispatch(changes: List<SectionChange>): DispatchPlan {
  return DispatchPlan(
    reload = false,
    rebuild = false,
    notifications = emptyList(),
    changedSections = emptyList(),
  )
}
