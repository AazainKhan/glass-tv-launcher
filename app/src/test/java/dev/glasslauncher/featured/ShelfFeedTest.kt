package dev.glasslauncher.featured

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dim hold's give-up path (offline, no feed for the new app): the old scene dims and the old Cover Flow stops. */
class ShelfFeedTest {
    @Test fun aFeedLoadedForAnotherAppIsNotTheNewAppsFeed() {
        // Spotify's feed stays in the state after Apple TV's fetch failed.
        assertFalse(ShelfFeed.owns("4:spotify", "4:appletv"))
        assertTrue(ShelfFeed.owns("4:appletv", "4:appletv"))
        assertFalse(ShelfFeed.owns(null, "4:a"))
    }

    @Test fun theOldCoverFlowStopsSteppingTheMomentFocusLeavesItsApp() {
        // Focus moved to Apple TV; the shelf app still says Spotify for the beat before it catches up.
        assertTrue(ShelfFeed.focusLeft("appletv", "spotify", followsFocus = true))
        assertFalse(ShelfFeed.focusLeft("spotify", "spotify", followsFocus = true))
    }

    @Test fun aFixedSourceOrAnUnsetShelfNeverCountsAsLeft() {
        assertFalse(ShelfFeed.focusLeft("appletv", null, followsFocus = true))
        assertFalse(ShelfFeed.focusLeft("appletv", "spotify", followsFocus = false))
        assertFalse(ShelfFeed.focusLeft(null, "spotify", followsFocus = true))
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
