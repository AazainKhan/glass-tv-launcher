package dev.glasslauncher.system

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.apps.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The switcher's real-task path (I08): never crashes without privilege, and falls back to usage history. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RecentTasksTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun tasksNeverThrowsWithoutPrivilege() {
        assertEquals(emptyList<RecentApps.RecentTask>(), RecentApps.tasks(ctx))
    }

    @Test fun glassOwnTasksAloneDoNotCountAsTaskAccess() {
        // An unprivileged getRecentTasks still returns Glass's own task: that must not hide the usage fallback.
        val own = listOf(RecentApps.RecentTask("dev.glasslauncher", 7))
        assertEquals(emptyList<RecentApps.RecentTask>(), RecentApps.others(own, "dev.glasslauncher"))
        val mixed = own + RecentApps.RecentTask("a", 8)
        assertEquals(listOf(RecentApps.RecentTask("a", 8)), RecentApps.others(mixed, "dev.glasslauncher"))
    }

    @Test fun resumeWithNoLiveTaskIsFalseSoTheCallerLaunchesNormally() {
        // No task for the package in a fresh list: nothing to move to the front, so the tap must fall through.
        assertFalse(RecentApps.resume(ctx, "dev.glasslauncher.nobody"))
    }

    @Test fun listFallsBackToGlassOwnLaunchesWithoutTaskAccessOrUsage() {
        val installed = listOf(AppEntry("a", "A", android.content.ComponentName("a", "A")))
        assertEquals(installed, RecentApps.list(ctx, installed, own = listOf("a")))
    }

    @Test fun aTaskUsedAfterItWasClosedComesBack() {
        // Booted 1 h ago; the app was closed 10 min ago (wall clock) and opened again 1 min ago (since boot).
        val now = 1_792_280_000_000L; val up = 3_600_000L
        val closedAt = now - 600_000L
        val active = RecentApps.wallTime(up - 60_000L, now, up)
        org.junit.Assert.assertEquals(now - 60_000L, active)
        org.junit.Assert.assertEquals(listOf("vlc"), RecentApps.order(listOf("vlc" to active), emptyList(), mapOf("vlc" to closedAt)))
        org.junit.Assert.assertEquals(0L, RecentApps.wallTime(0L, now, up))
    }
}
