package dev.glasslauncher.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppArtTest {
    private val html = """<img src="https://play-lh.googleusercontent.com/icon=s0-br30">
        <img srcset="https://play-lh.googleusercontent.com/phone=w526-h296 2x">
        <img src="https://play-lh.googleusercontent.com/phone=w1052-h592">
        <img src="https://play-lh.googleusercontent.com/promo=w526-h296">
        <img src="https://play-lh.googleusercontent.com/tv=w526-h296">"""

    @Test fun candidatesAreTheListingImagesInOrder() {
        assertEquals(
            listOf("phone", "promo", "tv").map { "https://play-lh.googleusercontent.com/$it" },
            AppArt.playCandidates(html),
        )
    }

    // The sizes in the page are thumbnail crops; only the originals say which image is a real 1080p TV shot.
    @Test fun picksTheFirstFullHdLandscapeOriginal() {
        val sizes = mapOf("phone" to (1080 to 2340), "promo" to (1024 to 500), "tv" to (1920 to 1080))
        val pick = AppArt.pick(AppArt.playCandidates(html)) { url -> sizes[url.substringAfterLast('/')] }
        assertEquals("https://play-lh.googleusercontent.com/tv=w1920-h1080", pick)
    }

    @Test fun noFullHdLandscapeIsNull() {
        assertNull(AppArt.pick(AppArt.playCandidates(html)) { 1280 to 720 })
    }
}
