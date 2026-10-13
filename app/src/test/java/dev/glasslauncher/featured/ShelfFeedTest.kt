package dev.glasslauncher.featured

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dim hold's give-up path (offline, no feed for the new app) without a gap on the cached path. */
class ShelfFeedTest {
    @Test fun theOldFeedStandsWhileTheNewAppsLoadIsUnderWay() {
        // Cached path: Apple TV's load is pending (settled key null, or still the previous app's).
        assertTrue(ShelfFeed.usable("4:spotify", "4:appletv", settledKey = null))
        assertTrue(ShelfFeed.usable("4:spotify", "4:appletv", settledKey = "4:spotify"))
    }

    @Test fun theOldFeedGoesOnceTheNewAppsLoadSettledWithoutOne() {
        // Offline: Apple TV's fetch failed, Spotify's feed is still in the state.
        assertFalse(ShelfFeed.usable("4:spotify", "4:appletv", settledKey = "4:appletv"))
    }

    @Test fun theNewAppsOwnFeedIsAlwaysUsable() {
        assertTrue(ShelfFeed.usable("4:appletv", "4:appletv", settledKey = "4:appletv"))
        assertTrue(ShelfFeed.usable("4:appletv", "4:appletv", settledKey = null))
    }

    @Test fun noFeedKeyMeansNoFeed() {
        assertFalse(ShelfFeed.usable(null, "4:a", null))
    }

    @Test fun theOldCoverFlowStopsSteppingTheMomentFocusLeavesItsApp() {
        assertTrue(ShelfFeed.focusLeft("appletv", "spotify", followsFocus = true))
        assertFalse(ShelfFeed.focusLeft("spotify", "spotify", followsFocus = true))
    }

    @Test fun aFixedSourceOrAnUnsetShelfNeverCountsAsLeft() {
        assertFalse(ShelfFeed.focusLeft("appletv", null, followsFocus = true))
        assertFalse(ShelfFeed.focusLeft("appletv", "spotify", followsFocus = false))
        assertFalse(ShelfFeed.focusLeft(null, "spotify", followsFocus = true))
    }

    @Test fun theHoldNeedsTheNewAppsSceneToBeMissingAndTheFeaturedBackground() {
        assertTrue(ShelfFeed.holds("appletv", "spotify", featuredBackground = true, reduceMotion = false))
        assertFalse(ShelfFeed.holds("appletv", "appletv", true, false))
        assertFalse(ShelfFeed.holds("appletv", null, true, false))
        assertFalse(ShelfFeed.holds(null, "spotify", true, false))
        assertFalse(ShelfFeed.holds("appletv", "spotify", featuredBackground = false, reduceMotion = false))
        assertFalse(ShelfFeed.holds("appletv", "spotify", featuredBackground = true, reduceMotion = true))
    }

    /** P143: the normal case keeps its hold; only Now Playing (its artwork stays whichever app has focus) skips it. */
    @Test fun noHoldWhileNowPlayingHasTheScreen() {
        assertTrue(ShelfFeed.holds("appletv", "spotify", featuredBackground = true, reduceMotion = false, nowPlaying = false))
        assertFalse(ShelfFeed.holds("appletv", "spotify", featuredBackground = true, reduceMotion = false, nowPlaying = true))
    }

    @Test fun noTitleForAnAppThatHasLostFocus() {
        // Right, Right 0.3 s apart (P130): focus is on the third app while the second's feed and title are still up.
        assertFalse(ShelfFeed.titleBelongsToFocus(focusLeft = true, hasFeed = true, stateKey = "nuvio", wantedKey = "nuvio"))
    }

    @Test fun noTitleFromThePreviousAppsFeedStandingInForALoad() {
        assertFalse(ShelfFeed.titleBelongsToFocus(focusLeft = false, hasFeed = true, stateKey = "nuvio", wantedKey = "stremio"))
        assertTrue(ShelfFeed.titleBelongsToFocus(focusLeft = false, hasFeed = true, stateKey = "stremio", wantedKey = "stremio"))
    }

    @Test fun withoutAFeedThereIsNothingToHold() {
        assertTrue(ShelfFeed.titleBelongsToFocus(focusLeft = false, hasFeed = false, stateKey = null, wantedKey = null))
    }
}
