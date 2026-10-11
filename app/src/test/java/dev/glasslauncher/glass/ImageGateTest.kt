package dev.glasslauncher.glass

import dev.glasslauncher.glass.ImageGate.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImageGateTest {
    @Before fun fresh() = ImageGate.forget()

    @Test fun aSquareCoverIsStretchedThreeTimes() {
        assertEquals(3f, ImageGate.scaleNeeded(640, 640), 0.01f)
        assertEquals(Level.Soft, ImageGate.level(640, 640))
    }

    @Test fun onlyTheShownPartCounts() {
        assertEquals(1f, ImageGate.scaleNeeded(1920, 1080), 0.001f)
        // A tall 2:3 poster is cropped to its width: 1920 wide shows at 1x.
        assertEquals(1f, ImageGate.scaleNeeded(1920, 2880), 0.001f)
        // A very wide strip is cropped to its height's worth of width.
        assertEquals(Level.Sharp, ImageGate.level(4000, 1125))
    }

    @Test fun theLimitIsOneAndAQuarter() {
        assertEquals(Level.Sharp, ImageGate.level(1536, 864)) // exactly 1.25x
        assertEquals(Level.Soft, ImageGate.level(1500, 844)) // 1.28x
        assertEquals(Level.Soft, ImageGate.level(1280, 720)) // 1.5x
        assertEquals(Level.Sharp, ImageGate.level(2000, 2000)) // the 2000 px album
    }

    @Test fun iconSizedAndPlaceholderArtIsPlain() {
        assertEquals(Level.Plain, ImageGate.level(128, 128))
        assertEquals(Level.Plain, ImageGate.level(199, 4000)) // a sliver
        assertEquals(Level.Plain, ImageGate.level(1920, 1080, placeholder = true))
        assertEquals(Level.Plain, ImageGate.level(0, 0))
    }

    @Test fun placeholdersAreRecognisedByTheirNames() {
        assertTrue(ImageGate.isPlaceholder("https://cdn.example.com/img/PLACEHOLDER_16x9.png"))
        assertTrue(ImageGate.isPlaceholder("android.resource://com.spotify.tv.android/drawable/tv_ps_banner"))
        assertFalse(ImageGate.isPlaceholder("https://i.scdn.co/image/ab67616d0000b273c25b"))
        assertFalse(ImageGate.isPlaceholder(null))
    }

    @Test fun aDecisionIsMadeOnceAndKept() {
        assertEquals(Level.Soft, ImageGate.decide("https://x/a.jpg", 640, 640))
        // Asked again with different numbers (a re-decode at another size): the first answer stands.
        assertEquals(Level.Soft, ImageGate.decide("https://x/a.jpg", 2000, 2000))
        assertEquals(Level.Sharp, ImageGate.decide("https://x/b.jpg", 2000, 2000))
    }

    @Test fun largerVariantsAreTriedFirst() {
        assertEquals(
            listOf("https://i.scdn.co/image/ab67616d000082c1abc", "https://i.scdn.co/image/ab67616d0000b273abc"),
            Variants.candidates("https://i.scdn.co/image/ab67616d0000b273abc"),
        )
        assertEquals(
            listOf("https://image.tmdb.org/t/p/original/x.jpg", "https://image.tmdb.org/t/p/w1280/x.jpg"),
            Variants.candidates("https://image.tmdb.org/t/p/w1280/x.jpg"),
        )
        assertEquals(listOf("https://example.com/a.jpg"), Variants.candidates("https://example.com/a.jpg"))
        // Already original: nothing to try.
        assertEquals(listOf("https://image.tmdb.org/t/p/original/x.jpg"), Variants.candidates("https://image.tmdb.org/t/p/original/x.jpg"))
    }
}
