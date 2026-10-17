package dev.glasslauncher

import dev.glasslauncher.system.RecentApps
import dev.glasslauncher.system.RecentApps.RecentTask
import org.junit.Assert.assertEquals
import org.junit.Test

/** P189: an app closed in the switcher leaves the list until it is used again, on builds that read the task stack. */
class RecentAppsCloseTest {
    private fun list(tasks: List<RecentTask>, own: List<String>, closed: Map<String, Long>) =
        RecentApps.order(RecentApps.usageFromTasks(tasks), own, closed)

    @Test fun aClosedAppStaysOutAfterHomeAndANewListEvenWhenTheStackStillHasIt() {
        val tasks = listOf(RecentTask("a", 1, 300), RecentTask("b", 2, 200), RecentTask("c", 3, 100))
        assertEquals(listOf("a", "b", "c"), list(tasks, emptyList(), emptyMap()))
        val closed = mapOf("a" to 400L, "b" to 400L)
        // Home, then the switcher again: the same stack, rebuilt later. They used to be stamped "now" and came straight back.
        assertEquals(listOf("c"), list(tasks, emptyList(), closed))
    }

    @Test fun anAppUsedAgainAfterTheCloseIsListedAgain() {
        val closed = mapOf("a" to 400L)
        assertEquals(listOf("a", "b"), list(listOf(RecentTask("a", 9, 500), RecentTask("b", 2, 200)), emptyList(), closed))
    }

    @Test fun withoutATaskTimeAClosedAppStaysOutAndOthersKeepTheStacksOrder() {
        val tasks = listOf(RecentTask("a", 1), RecentTask("b", 2), RecentTask("c", 3))
        assertEquals(listOf("a", "b", "c"), list(tasks, emptyList(), emptyMap()))
        assertEquals(listOf("a", "c"), list(tasks, emptyList(), mapOf("b" to 10L)))
    }

    @Test fun anAppOpenedThroughGlassAfterBeingClosedIsNoLongerClosed() {
        // HomeModel.launch drops the entry from closedRecents; with it gone the app is listed again.
        assertEquals(listOf("a"), list(listOf(RecentTask("a", 1)), emptyList(), emptyMap()))
    }
}
