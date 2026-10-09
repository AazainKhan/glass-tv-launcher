package dev.glasslauncher.system

import org.junit.Assert.assertEquals
import org.junit.Test

/** The switcher's list: newest first, and an app closed from it stays out until it is next in the foreground. */
class RecentAppsOrderTest {
    private val usage = listOf("a" to 100L, "b" to 300L, "c" to 200L)

    @Test fun newestForegroundFirstThenOwnLaunches() {
        assertEquals(listOf("b", "c", "a", "x"), RecentApps.order(usage, listOf("x", "a"), emptyMap()))
    }

    @Test fun aClosedAppStaysOutWhileItsLastForegroundIsOlderThanTheClose() {
        // c was in the foreground at 200 and was closed at 250: the usage history still says 200.
        assertEquals(listOf("b", "a"), RecentApps.order(usage, emptyList(), mapOf("c" to 250L)))
    }

    @Test fun anAppComesBackWhenItIsInTheForegroundAfterTheClose() {
        assertEquals(listOf("b", "c", "a"), RecentApps.order(usage, emptyList(), mapOf("c" to 150L)))
    }

    @Test fun aClosedAppFromOwnLaunchesAlsoStaysOutUntilSeenAgain() {
        // x was only launched through Glass (no foreground time known): closing it drops it.
        assertEquals(listOf("b", "c", "a"), RecentApps.order(usage, listOf("x"), mapOf("x" to 500L)))
    }

    @Test fun anOwnLaunchAfterTheCloseIsRecordedAgainByTheModelSoItReturns() {
        // The model removes a closed app from recentApps and re-adds it on launch; the closed marker alone
        // doesn't hide it once the system saw it in the foreground later than the close.
        assertEquals(listOf("c", "b", "a"), RecentApps.order(listOf("a" to 100L, "b" to 300L, "c" to 600L), listOf("c"), mapOf("c" to 500L)))
    }

    @Test fun withoutUsageAccessARelaunchedClosedAppComesBackThroughTheModel() {
        // No usage history at all: the list is only Glass's own record. Closing drops the app; launching it
        // again through Glass records it and clears the closed marker, so it returns.
        val closed = dev.glasslauncher.data.LauncherConfig(recentApps = listOf("x"), closedRecents = emptyMap())
        val afterClose = closed.copy(recentApps = closed.recentApps - "x", closedRecents = mapOf("x" to 500L))
        assertEquals(emptyList<String>(), RecentApps.order(emptyList(), afterClose.recentApps, afterClose.closedRecents))
        val relaunched = dev.glasslauncher.home.HomeModel.launchedConfig(afterClose, "x")
        assertEquals(listOf("x"), RecentApps.order(emptyList(), relaunched.recentApps, relaunched.closedRecents))
    }
}
