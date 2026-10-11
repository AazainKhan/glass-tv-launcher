package dev.glasslauncher.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyArtTest {
    @Test fun anAlbumCoverHasA2000pxVariant() {
        assertEquals(
            "https://i.scdn.co/image/ab67616d000082c1c25b53f0a34aa1bb4f77cb3e",
            SpotifyArt.large("https://i.scdn.co/image/ab67616d0000b273c25b53f0a34aa1bb4f77cb3e"),
        )
    }

    @Test fun artistsAndOtherArtHaveNone() {
        assertNull(SpotifyArt.large("https://i.scdn.co/image/ab6761610000e5ebc1719ac9e6a75c1c25835018")) // 640 is their largest
        assertNull(SpotifyArt.large("android.resource://com.spotify.tv.android/drawable/tv_ps_banner"))
        assertNull(SpotifyArt.large("https://example.com/ab67616d0000b273abc.jpg"))
    }
}
