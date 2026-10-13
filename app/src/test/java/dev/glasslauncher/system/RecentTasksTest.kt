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
}
