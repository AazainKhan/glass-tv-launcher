package dev.glasslauncher.system

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** P87: the instant-back gate asks the system's usage events whether the launched app has really come to the foreground. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ForegroundSinceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()

    private fun allow(mode: Int) {
        shadowOf(ctx.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName, mode)
    }

    @Test fun withoutUsageAccessItAssumesTheAppHasTheForegroundSoBackIsNeverTaken() {
        allow(AppOpsManager.MODE_IGNORED)
        assertTrue(RecentApps.foregroundSince(ctx, since = 1_000, ownPkg = ctx.packageName, now = 5_000))
    }

    @Test fun anotherAppMovingToTheForegroundAfterTheLaunchCloses() {
        allow(AppOpsManager.MODE_ALLOWED)
        val usm = shadowOf(ctx.getSystemService(UsageStatsManager::class.java))
        usm.addEvent("com.netflix.ninja", 3_000, UsageEvents.Event.MOVE_TO_FOREGROUND)
        assertTrue(RecentApps.foregroundSince(ctx, since = 2_000, ownPkg = ctx.packageName, now = 5_000))
    }

    @Test fun onlyGlassItselfOrAnEarlierEventDoesNot() {
        allow(AppOpsManager.MODE_ALLOWED)
        val usm = shadowOf(ctx.getSystemService(UsageStatsManager::class.java))
        usm.addEvent(ctx.packageName, 3_000, UsageEvents.Event.MOVE_TO_FOREGROUND)
        usm.addEvent("com.earlier.app", 100, UsageEvents.Event.MOVE_TO_FOREGROUND)
        assertFalse(RecentApps.foregroundSince(ctx, since = 2_000, ownPkg = ctx.packageName, now = 5_000))
    }
}
