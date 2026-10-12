package dev.glasslauncher.featured

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dim hold's give-up path (offline, no feed for the new app): the old scene dims and the old Cover Flow stops. */
class ShelfFeedTest {
    @Test fun aFeedLoadedForAnotherAppIsNotTheNewAppsFeed() {
        // Spotify's feed stays in the state after Apple TV's fetch failed.
        assertFalse(ShelfFeed.owns("4:spotify", "4:appletv", "appletv", "appletv", followsFocus = true))
        assertTrue(ShelfFeed.owns("4:appletv", "4:appletv", "appletv", "appletv", followsFocus = true))
    }

    @Test fun noKeyMeansNoFeed() {
        assertFalse(ShelfFeed.owns(null, "4:a", "a", "a", followsFocus = true))
    }

    @Test fun theOldFeedStopsTheMomentFocusLeavesItsApp() {
        // Focus moved to Apple TV; the shelf app (and its key) still say Spotify for the 250 ms beat.
        assertFalse(ShelfFeed.owns("4:spotify", "4:spotify", "appletv", "spotify", followsFocus = true))
        // Back on Spotify before the shelf moved: the feed is the right one again.
        assertTrue(ShelfFeed.owns("4:spotify", "4:spotify", "spotify", "spotify", followsFocus = true))
    }

    @Test fun aFixedSourceIgnoresWhichAppIsFocused() {
        assertTrue(ShelfFeed.owns("4:fixed", "4:fixed", "appletv", null, followsFocus = false))
    }

    @Test fun theHoldEngagesWhenTheNewAppHasNoFeedOrScene() {
        // Focus on Apple TV, the scene on screen is still Spotify's: dim it (the 2 s give-up then lets go).
        assertTrue(ShelfFeed.holds("appletv", "spotify", featuredBackground = true, reduceMotion = false))
    }

    @Test fun theHoldReleasesOnceTheSceneIsTheNewAppsOrNotWanted() {
        assertFalse(ShelfFeed.holds("appletv", "appletv", true, false))
        assertFalse(ShelfFeed.holds("appletv", null, true, false))
        assertFalse(ShelfFeed.holds(null, "spotify", true, false))
        assertFalse(ShelfFeed.holds("appletv", "spotify", featuredBackground = false, reduceMotion = false))
        assertFalse(ShelfFeed.holds("appletv", "spotify", featuredBackground = true, reduceMotion = true))
    }
}
