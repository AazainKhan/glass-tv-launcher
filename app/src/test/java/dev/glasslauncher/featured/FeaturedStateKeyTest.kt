package dev.glasslauncher.featured

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.data.FeaturedConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * P113: the repository says which key its feed was loaded for and which load has settled, so the shelf keeps the old
 * feed while a load is under way (cached path, no gap) and drops it once the load failed (offline).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FeaturedStateKeyTest {
    private val a = FeaturedConfig(stremioCatalog = "movie/top")
    private val b = FeaturedConfig(stremioCatalog = "series/top")

    @Test fun theOldFeedStandsWhileTheLoadIsUnderWayAndGoesWhenItFails() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DiskFeedCache(File(context.cacheDir, "featured")).write(cacheKey(a), FeaturedFeed("A", emptyList()))
        lateinit var repo: FeaturedRepository
        val seenAtRequest = ArrayList<FeaturedState>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            seenAtRequest += repo.state.value
            throw IOException("offline")
        }.build()
        repo = FeaturedRepository(context, http)

        // A: its cached feed is read, the fetch fails: A's feed, A's key, settled.
        repo.refresh(a)
        assertEquals("A", repo.state.value.feed?.heading)
        assertEquals(cacheKey(a), repo.state.value.key)
        assertEquals(cacheKey(a), repo.state.value.settledKey)

        // B: no feed of its own. While its request is in flight A's feed stands and B has not settled ...
        repo.refresh(b)
        val underWay = seenAtRequest.last()
        assertEquals(cacheKey(a), underWay.key)
        assertNull(underWay.settledKey)
        assertTrue(ShelfFeed.usable(underWay.key, cacheKey(b), underWay.settledKey))
        // ... and once it has failed, A's feed is not B's any more.
        val settled = repo.state.value
        assertEquals(cacheKey(a), settled.key)
        assertEquals(cacheKey(b), settled.settledKey)
        assertFalse(ShelfFeed.usable(settled.key, cacheKey(b), settled.settledKey))
    }
}
