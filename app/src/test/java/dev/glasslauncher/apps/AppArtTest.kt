package dev.glasslauncher.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppArtTest {
    // The hero is the app's own logo art: Amazon's 16:9 Fire TV icon, never a background collage or a screenshot.
    @Test fun usesTheFireTvIconNotTheBackground() {
        val tile = """{"packageName":"com.netflix.ninja","tvIconUrl":"https://m.media-amazon.com/images/I/icon.png",
            "tvBackgroundImageUrl":"https://m.media-amazon.com/images/I/bg.jpg","iconUrl":"https://m.media-amazon.com/images/I/small.png"}"""
        assertEquals("com.netflix.ninja" to "https://m.media-amazon.com/images/I/icon.png", AppArt.fireTvIcon(tile))
    }

    @Test fun tilesWithoutAnIconAreSkipped() {
        assertNull(AppArt.fireTvIcon("""{"packageName":"com.example","tvBackgroundImageUrl":"https://x/bg.jpg"}"""))
        assertNull(AppArt.fireTvIcon("not json"))
    }
}
