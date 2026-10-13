package dev.glasslauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingCapsTest {
    private val seek = NowPlayingCaps.ACTION_SEEK_TO
    private val rate = NowPlayingCaps.ACTION_SET_RATING
    private fun like(actions: Long = 0, type: Int = 0, userLiked: Boolean? = null, vararg custom: Pair<String, String?>) =
        NowPlayingCaps.like(actions, type, userLiked, custom.toList())

    // --- seek ---
    @Test fun seekNeedsTheActionAndALength() {
        assertTrue(NowPlayingCaps.canSeek(seek or 4, 200_000))
        assertFalse("no SEEK_TO action", NowPlayingCaps.canSeek(4, 200_000))
        assertFalse("live stream", NowPlayingCaps.canSeek(seek, 0))
        assertFalse(NowPlayingCaps.canSeek(seek, -1))
    }

    @Test fun durationRejectsLiveNegativeAndAbsurd() {
        assertEquals(200_000, NowPlayingCaps.duration(200_000))
        assertEquals(0, NowPlayingCaps.duration(-1))
        assertEquals(0, NowPlayingCaps.duration(0))
        assertEquals(0, NowPlayingCaps.duration(Long.MAX_VALUE))
        assertEquals(0, NowPlayingCaps.duration(Long.MIN_VALUE))
    }

    @Test fun positionAndSpeedAreClamped() {
        assertEquals(0, NowPlayingCaps.position(-1, 100))
        assertEquals(100, NowPlayingCaps.position(5_000, 100))
        assertEquals(7, NowPlayingCaps.position(7, 0))
        assertEquals(1f, NowPlayingCaps.speed(Float.NaN), 0f)
        assertEquals(1f, NowPlayingCaps.speed(-2f), 0f)
        assertEquals(1f, NowPlayingCaps.speed(0f), 0f)
        assertEquals(1f, NowPlayingCaps.speed(1e9f), 0f)
        assertEquals(1.5f, NowPlayingCaps.speed(1.5f), 0f)
        assertEquals(1_000, NowPlayingCaps.updatedAt(5_000_000, 1_000))
        assertEquals(1_000, NowPlayingCaps.updatedAt(0, 1_000))
        assertEquals(500, NowPlayingCaps.updatedAt(500, 1_000))
    }

    // --- like: ratings ---
    @Test fun heartRatingNeedsTheTypeAndSetRating() {
        assertEquals(Like.Heart(false), like(rate, NowPlayingCaps.RATING_HEART))
        assertEquals(Like.Heart(true), like(rate, NowPlayingCaps.RATING_HEART, userLiked = true))
        assertEquals("type alone is not enough", Like.None, like(0, NowPlayingCaps.RATING_HEART))
    }

    @Test fun thumbRatingReflectsTheUserRating() {
        assertEquals(Like.ThumbUpDown(true), like(rate, NowPlayingCaps.RATING_THUMB_UP_DOWN, userLiked = true))
        assertEquals("thumb down is not liked", Like.ThumbUpDown(false), like(rate, NowPlayingCaps.RATING_THUMB_UP_DOWN, userLiked = false))
        assertEquals(Like.None, like(rate, NowPlayingCaps.RATING_NONE))
        assertEquals("other rating styles (stars, percent) are not a like", Like.None, like(rate, 3))
    }

    @Test fun ratingBeatsACustomAction() {
        assertEquals(Like.Heart(false), like(rate, NowPlayingCaps.RATING_HEART, custom = arrayOf("com.spotify.music.action.like" to "Like")))
    }

    // --- like: custom actions ---
    @Test fun spotifyLikeAction() {
        assertEquals(Like.Custom("com.spotify.music.action.like", false), like(custom = arrayOf("com.spotify.music.action.like" to "Add to Liked Songs")))
    }

    @Test fun spotifyUnlikeMeansLiked() {
        assertEquals(Like.Custom("com.spotify.music.action.unlike", true), like(custom = arrayOf("com.spotify.music.action.unlike" to "Remove from Liked Songs")))
    }

    @Test fun youTubeMusicThumbsUp() {
        assertEquals(Like.Custom("THUMBS_UP", false), like(custom = arrayOf("THUMBS_UP" to "Thumbs up")))
        assertEquals(Like.Custom("action.ThumbsUp", false), like(custom = arrayOf("action.ThumbsUp" to null)))
    }

    @Test fun favoriteAndHeartNames() {
        assertEquals(Like.Custom("fav", false), like(custom = arrayOf("fav" to "Add to Favorites")))
        assertEquals(Like.Custom("h", true), like(custom = arrayOf("h" to "Unfavourite")))
        assertEquals(Like.Custom("x", false), like(custom = arrayOf("x" to "Heart")))
    }

    @Test fun dislikeAndThumbsDownAreNotLikes() {
        assertEquals(Like.None, like(custom = arrayOf("com.app.dislike" to "Dislike")))
        assertEquals(Like.None, like(custom = arrayOf("THUMBS_DOWN" to "Thumbs down")))
    }

    @Test fun likeIsPickedOverItsDislikeSibling() {
        val picked = like(custom = arrayOf("dislike" to "Dislike", "like" to "Like", "next30" to "Skip 30 s"))
        assertEquals(Like.Custom("like", false), picked)
    }

    @Test fun unrelatedActionsGiveNothing() {
        assertEquals(Like.None, like())
        assertEquals(Like.None, like(custom = arrayOf("shuffle" to "Shuffle", "repeat" to "Repeat", "remove_queue" to "Remove from queue")))
        assertEquals("'unlikely' is not 'unlike'", Like.None, like(custom = arrayOf("unlikely" to "Unlikely")))
    }

    @Test fun stateLabelAlone() = assertEquals(Like.Custom("s", true), like(custom = arrayOf("s" to "Liked")))

    @Test fun hugeIdsAreCapped() {
        assertEquals(Like.None, like(custom = arrayOf(("like" + "x".repeat(10_000)) to "Shuffle")))
        assertEquals("oversized ids are skipped", Like.None, like(custom = arrayOf(("a.like." + "x".repeat(10_000)) to "Like")))
        assertEquals("control characters too", Like.None, like(custom = arrayOf("like\u0000" to "Like")))
    }

    // --- sanitising ---
    @Test fun textStripsControlsTrimsAndCaps() {
        assertEquals("Song", NowPlayingCaps.text("  Song\u0000\u0007 "))
        assertEquals("a b", NowPlayingCaps.text("a\nb"))
        assertNull(NowPlayingCaps.text("\u0000\u0001  "))
        assertNull(NowPlayingCaps.text(null))
        assertEquals(200, NowPlayingCaps.text("x".repeat(5_000))!!.length)
    }

    // --- model: seek, extrapolation, liking ---
    private class Spy : NowPlaying.Controls {
        val log = ArrayList<String>()
        override fun play() { log += "play" }
        override fun pause() { log += "pause" }
        override fun next() {}
        override fun previous() {}
        override fun seekTo(positionMs: Long) { log += "seek $positionMs" }
        override fun setHeart(liked: Boolean) { log += "heart $liked" }
        override fun setThumbUp(liked: Boolean) { log += "thumb $liked" }
        override fun customAction(id: String) { log += "custom $id" }
    }

    private fun np(spy: Spy, pos: Long = 30_000, at: Long = 1_000, speed: Float = 1f, playing: Boolean = true, canSeek: Boolean = true, like: Like = Like.None, dur: Long = 200_000) =
        NowPlaying("T", "A", null, "pkg", null, dur, pos, at, speed, playing, spy, canSeek, like)

    @Test fun positionExtrapolatesWhilePlayingAndHoldsWhenPaused() {
        val s = Spy()
        assertEquals(30_000, np(s).positionNow(now = 1_000))
        assertEquals(35_000, np(s).positionNow(now = 6_000))
        assertEquals(40_000, np(s, speed = 2f).positionNow(now = 6_000))
        assertEquals(30_000, np(s, playing = false).positionNow(now = 60_000))
        assertEquals("never past the end", 200_000, np(s).positionNow(now = 9_999_999))
    }

    @Test fun seekByClampsToTheItem() {
        val s = Spy()
        // positionNow() reads the real clock; a paused item makes it deterministic.
        np(s, pos = 5_000, playing = false).seekBy(-10_000)
        np(s, pos = 195_000, playing = false).seekBy(10_000)
        np(s, pos = 50_000, playing = false).seekBy(10_000)
        assertEquals(listOf("seek 0", "seek 200000", "seek 60000"), s.log)
    }

    @Test fun seekByDoesNothingWithoutTheCapability() {
        val s = Spy()
        np(s, canSeek = false, playing = false).seekBy(10_000)
        np(s, dur = 0, playing = false).seekBy(10_000)
        assertTrue(s.log.isEmpty())
    }

    @Test fun toggleLikeUsesTheRightMechanism() {
        val s = Spy()
        np(s, like = Like.Heart(false)).toggleLike()
        np(s, like = Like.Heart(true)).toggleLike()
        np(s, like = Like.ThumbUpDown(false)).toggleLike()
        np(s, like = Like.ThumbUpDown(true)).toggleLike()
        np(s, like = Like.Custom("c.like", false)).toggleLike()
        np(s, like = Like.None).toggleLike()
        assertEquals(listOf("heart true", "heart false", "thumb true", "thumb false", "custom c.like"), s.log)
    }

    @Test fun aChangedLikeOrSeekCapabilityIsNotDroppedAsADuplicate() {
        val s = Spy()
        assertTrue(sameState(np(s), np(s)))
        assertFalse(sameState(np(s, like = Like.Heart(false)), np(s, like = Like.Heart(true))))
        assertFalse(sameState(np(s, canSeek = false), np(s, canSeek = true)))
        assertEquals("the like never changes the track identity", np(s, like = Like.Heart(true)).key, np(s).key)
    }

    // --- seek coalescer, fake clock ---
    @Test fun firstPressSendsAtOnceLaterOnesWaitForTheGap() {
        val c = SeekCoalescer(minGapMs = 200)
        assertEquals(40_000L, c.press("k", 30_000, 200_000, 10_000, now = 0))
        assertNull(c.press("k", 30_000, 200_000, 10_000, now = 50))
        assertNull(c.press("k", 30_000, 200_000, 10_000, now = 100))
        assertEquals(100L, c.delayMs(now = 100))
        assertEquals("trailing flush carries every press", 60_000L, c.flush("k", now = 200))
        assertNull(c.flush("k", now = 210))
        assertNull(c.delayMs(210))
    }

    @Test fun holdSendsAtMostFivePerSecond() {
        val c = SeekCoalescer(minGapMs = 200)
        var sent = 0
        var t = 0L
        while (t < 1_000) { // a 30 Hz key repeat for a second
            if (c.press("k", 0, 600_000, 10_000, t) != null) sent++
            t += 33
        }
        assertTrue("sent $sent", sent in 1..6)
    }

    @Test fun targetsAccumulateFromTheTargetNotTheStalePosition() {
        val c = SeekCoalescer()
        c.press("k", 100_000, 600_000, 10_000, 0)
        c.press("k", 100_000, 600_000, 10_000, 10) // the session still says 100 s
        assertEquals(120_000L, c.flush("k", 400))
        assertEquals(120_000L, c.shown(500))
    }

    @Test fun targetsClampAndSettle() {
        val c = SeekCoalescer()
        assertEquals(0L, c.press("k", 4_000, 100_000, -10_000, 0))
        assertEquals(100_000L, c.press("k", 99_000, 100_000, 10_000, 5_000)) // settled: the base is the real position again
        assertNull("a settled bar returns to the session", c.shown(10_000))
    }

    @Test fun aNewTrackDropsThePendingSeek() {
        val c = SeekCoalescer()
        c.press("old", 10_000, 100_000, 10_000, 0)
        c.press("old", 10_000, 100_000, 10_000, 10) // pending
        assertNull("the old target is never sent to the new track", c.flush("new", 300))
        assertEquals(20_000L, c.press("new", 10_000, 100_000, 10_000, 300))
        c.reset(); assertNull(c.delayMs(0)); assertNull(c.shown(0))
    }

    @Test fun noDurationNoSeek() = assertNull(SeekCoalescer().press("k", 0, 0, 10_000, 0))
}
