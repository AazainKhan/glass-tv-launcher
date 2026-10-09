package dev.glasslauncher.apps

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Tiles are drawn once: shared while loading, kept on disk across runs, and preloaded in the order given. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TileCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.cacheDir, "tile-cache-test")

    @Before fun clean() { dir.deleteRecursively() }

    private fun art(disk: Boolean = true) = TileArt(context, IconPacks(context), if (disk) TileDiskCache(dir) else null)
    private fun app(n: Int, updated: Long = 1_000L + n) =
        AppEntry("com.example.app$n", "App $n", ComponentName("com.example.app$n", "Main"), updated = updated)
    private fun spec(n: Int, updated: Long = 1_000L + n) = TileSpec(app(n, updated), null, null)

    @Test fun concurrentLoadsOfOneTileDrawItOnce() = runBlocking {
        val art = art(disk = false)
        val tiles = (1..8).map { async { art.loadTile(spec(1)) } }.awaitAll()
        assertEquals(1, art.rendered)
        assertTrue("every caller gets the same tile", tiles.all { it === tiles.first() })
    }

    @Test fun aTileDrawnBeforeIsReadBackFromDiskNotDrawnAgain() = runBlocking {
        val first = art()
        first.loadTile(spec(1))
        assertEquals(1, first.rendered)
        // A new process: an empty memory cache over the same directory.
        val second = art()
        val tile = second.loadTile(spec(1))
        assertEquals("served from disk", 0, second.rendered)
        assertEquals(TileArt.WIDTH, tile.image.width)
    }

    @Test fun aCallerThatLeavesDoesNotCancelTheRenderOthersWaitOn() = runBlocking {
        val art = art(disk = false)
        val leaver = kotlinx.coroutines.GlobalScope.run { async(kotlinx.coroutines.Dispatchers.Default) { art.loadTile(spec(1)) } }
        leaver.cancel()
        val tile = art.loadTile(spec(1))
        assertNotNull(tile)
        assertEquals("drawn once even though the first caller left", 1, art.rendered)
    }

    @Test fun anUpdatedAppIsDrawnAgainNotServedItsOldTile() = runBlocking {
        art().loadTile(spec(1, updated = 1_000L))
        val after = art()
        after.loadTile(spec(1, updated = 2_000L))
        assertEquals("a different update time is a different tile", 1, after.rendered)
    }

    @Test fun anAppWithNoUpdateTimeIsNeverCachedOnDisk() = runBlocking {
        art().loadTile(spec(1, updated = 0L))
        assertEquals(0, TileDiskCache(dir).count())
    }

    @Test fun preloadFillsTheCacheInTheGivenOrderAndStopsAtTheLimit() = runBlocking {
        val art = art(disk = false)
        art.preload((1..6).map { spec(it) }, limit = 3)
        val deadline = System.currentTimeMillis() + 10_000
        while (art.peekTile(spec(3)) == null && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(20)
        assertNotNull(art.peekTile(spec(1)))
        assertNotNull(art.peekTile(spec(3)))
        kotlinx.coroutines.delay(300)
        assertNull("past the limit is not preloaded", art.peekTile(spec(4)))
    }

    @Test fun theDiskCacheKeepsTheNewestTiles() {
        val cache = TileDiskCache(dir, maxFiles = 3)
        val bmp = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        for (i in 1..6) cache.put("k$i", bmp)
        // Make older files older, then trim.
        dir.listFiles()!!.sortedBy { it.name }.forEachIndexed { i, f -> f.setLastModified(1_000L * (i + 1)) }
        cache.trim()
        assertEquals(3, cache.count())
    }
}
