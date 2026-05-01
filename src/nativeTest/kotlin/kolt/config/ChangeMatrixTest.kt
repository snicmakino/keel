package kolt.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangeMatrixTest {
  @Test
  fun planDispatchOfEmptyChangesReturnsEmptyPlan() {
    val plan = planDispatch(emptyList())

    assertEquals(false, plan.reload)
    assertEquals(false, plan.rebuild)
    assertTrue(plan.notifications.isEmpty())
    assertTrue(plan.changedSections.isEmpty())
  }

  @Test
  fun notificationMarkerIsExposed() {
    assertEquals("[watch] ⚠", NOTIFICATION_MARKER)
  }
}
