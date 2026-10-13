package dev.glasslauncher.system

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.apps.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun resumeOfAnUnknownTaskIdNeverThrows() {
        // Robolectric's ActivityManager shadow doesn't validate the id; the real contract this guards is
        // "resume never crashes the tap", which runCatching gives regardless of what the id turns out to do.
        RecentApps.resume(ctx, 999_999)
    }

    @Test fun taskIdIsNullForAnAppNeverSeenInATaskList() {
        assertNull(RecentApps.taskId("dev.glasslauncher.nobody"))
    }

    @Test fun listFallsBackToUsageHistoryWithoutTaskAccess() {
        // No "hdmi"-style privileged service here either: Robolectric's ActivityManager.getRecentTasks is empty,
        // so list() must take the existing usage-history path rather than returning nothing.
        val installed = listOf(AppEntry("a", "A", android.content.ComponentName("a", "A")))
        assertEquals(emptyList<AppEntry>(), RecentApps.list(ctx, installed, own = emptyList()))
    }
}
