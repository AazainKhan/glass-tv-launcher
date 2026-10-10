package dev.glasslauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.shots.TV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Art that fails to load is retried and never leaves an empty frame: the title on a tint stands in. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class ReliableImageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backoffGrowsAndIsBounded() {
        val waits = (0..5).map { ArtRetry.delayMs(it) }
        assertTrue("grows: $waits", waits.zipWithNext().take(4).all { (a, b) -> b > a })
        assertEquals("bounded", waits[4], waits[5])
    }

    @Test fun noUrlShowsTheFallbackAtOnce() {
        compose.setContent { ReliableImage(null, Modifier.size(150.dp, 84.dp), title = "Discover Weekly") }
        compose.onNodeWithTag("art-fallback").assertExists()
    }

    @Test fun anImageThatCannotLoadIsRetriedThenEndsOnTheFallbackAndFocusStartsAgain() {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Int>()
        var focused by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            Box(Modifier.size(150.dp, 84.dp)) {
                ReliableImage(
                    "file:///definitely/not/there.jpg", Modifier.size(150.dp, 84.dp), title = "Missing Album",
                    focused = focused, retryDelay = { 5L }, onRequest = { requests += it },
                )
            }
        }
        // First request plus MAX_ATTEMPTS retries, then it rests on the fallback.
        val end = System.currentTimeMillis() + 20_000
        while (requests.size < ArtRetry.MAX_ATTEMPTS + 1 && System.currentTimeMillis() < end) { compose.mainClock.advanceTimeBy(50); Thread.sleep(20) }
        compose.waitForIdle()
        Thread.sleep(300)
        assertEquals("one request, then the retries", (0..ArtRetry.MAX_ATTEMPTS).toList(), requests.toList())
        compose.onNodeWithTag("art-fallback").assertExists()
        // Focus on a card whose art never came starts a fresh run.
        focused = true
        compose.waitForIdle()
        val end2 = System.currentTimeMillis() + 20_000
        while (requests.size <= ArtRetry.MAX_ATTEMPTS + 1 && System.currentTimeMillis() < end2) { compose.mainClock.advanceTimeBy(50); Thread.sleep(20) }
        assertTrue("focus asked again: $requests", requests.size > ArtRetry.MAX_ATTEMPTS + 1)
        assertEquals("each request is a new one", requests.toList().distinct(), requests.toList())
        compose.onNodeWithTag("art-fallback").assertExists()
    }

    @Test fun whenTheNetworkReturnsAFailedImageAsksAgainWithoutRefocus() {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Int>()
        compose.setContent {
            Box(Modifier.size(150.dp, 84.dp)) {
                ReliableImage("file:///definitely/not/there.jpg", Modifier.size(150.dp, 84.dp), title = "Missing", retryDelay = { 5L }, onRequest = { requests += it })
            }
        }
        val end = System.currentTimeMillis() + 20_000
        while (requests.size < ArtRetry.MAX_ATTEMPTS + 1 && System.currentTimeMillis() < end) { compose.mainClock.advanceTimeBy(50); Thread.sleep(20) }
        Thread.sleep(300)
        compose.waitForIdle()
        val before = requests.size
        assertEquals(ArtRetry.MAX_ATTEMPTS + 1, before)
        NetworkEpoch.bump() // connectivity came back
        compose.waitForIdle()
        val end2 = System.currentTimeMillis() + 20_000
        while (requests.size <= before && System.currentTimeMillis() < end2) { compose.mainClock.advanceTimeBy(50); Thread.sleep(20) }
        assertTrue("asked again after the network returned: $requests", requests.size > before)
    }
}
