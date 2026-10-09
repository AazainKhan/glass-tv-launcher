package dev.glasslauncher.apps

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** An updated app must not be served its old tile: the version is part of the cache key. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TileVersionTest {
    private val app = AppEntry("com.example.tv", "Example", ComponentName("com.example.tv", "Main"), updated = 1_000L)

    @Test fun anUpdatedAppHasADifferentTileKey() {
        assertNotEquals(TileSpec(app, null, null), TileSpec(app.copy(updated = 2_000L), null, null))
        assertNotEquals(TileSpec(app, null, null).hashCode(), TileSpec(app.copy(updated = 2_000L), null, null).hashCode())
    }

    @Test fun theSameVersionKeepsTheSameKey() {
        assertEquals(TileSpec(app, null, null), TileSpec(app.copy(), null, null))
    }

    @Test fun anEntryBuiltWithoutAVersionStillWorks() {
        assertEquals(0L, AppEntry("a", "A", ComponentName("a", "M")).updated)
    }

    @Test fun theRepositoryReportsWhenEachPackageWasLastUpdated() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val pm = shadowOf(context.packageManager)
        val pkg = "com.example.updated"
        fun install(at: Long) {
            val info = ApplicationInfo().apply { packageName = pkg; name = "Updated"; nonLocalizedLabel = "Updated"; flags = ApplicationInfo.FLAG_INSTALLED }
            pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = info; lastUpdateTime = at })
            pm.addOrUpdateActivity(ActivityInfo().apply { packageName = pkg; name = "$pkg.Main"; nonLocalizedLabel = "Updated"; applicationInfo = info; exported = true })
            pm.addIntentFilterForActivity(ComponentName(pkg, "$pkg.Main"), IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) })
        }
        val repo = AppRepository(context)
        install(1_111L)
        val first = repo.apps().first().single { it.packageName == pkg }
        assertEquals(1_111L, first.updated)
        install(2_222L)
        val after = repo.apps().first().single { it.packageName == pkg }
        assertEquals(2_222L, after.updated)
        assertNotEquals("the updated app must not hit the old tile", TileSpec(first, null, null), TileSpec(after, null, null))
    }
}
