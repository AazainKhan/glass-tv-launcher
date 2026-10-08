package dev.glasslauncher.featured

import dev.glasslauncher.data.FeaturedConfig
import dev.glasslauncher.data.FeaturedMode
import dev.glasslauncher.data.FeaturedSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSourcesTest {
    private val focused = FeaturedConfig(mode = FeaturedMode.FocusedApp, tmdbKey = "k")

    @Test fun oneSourceIgnoresFocus() {
        val cfg = FeaturedConfig(source = FeaturedSourceId.Stremio)
        assertEquals(cfg, AppSources.effective(cfg, "com.netflix.ninja"))
    }

    @Test fun focusedAppUsesItsService() {
        val c = AppSources.effective(focused, "com.netflix.ninja")!!
        assertEquals(FeaturedSourceId.JustWatch, c.source)
        assertEquals("nfx", c.justWatchPackage)
    }

    @Test fun streamingServicesNeedNoKey() {
        val c = AppSources.effective(FeaturedConfig(mode = FeaturedMode.FocusedApp), "com.amazon.firebat")!!
        assertEquals(FeaturedSourceId.JustWatch, c.source)
        assertEquals("amp", c.justWatchPackage)
    }

    @Test fun primeLinksOpenInFireTvsPrimeApp() {
        val link = "intent://app.primevideo.com/watch?gti=x#Intent;package=com.amazon.amazonvideo.livingroom;scheme=https;end"
        assertEquals("intent://app.primevideo.com/watch?gti=x#Intent;package=com.amazon.firebat;scheme=https;end", JustWatch.forFireTv(link))
    }

    // tvOS: an app with nothing of its own to show keeps its hero (logo or screen), not another app's titles.
    @Test fun serviceWithoutKeyKeepsTheAppHero() {
        assertNull(AppSources.effective(focused, "com.amazon.firetv.youtube"))
    }

    @Test fun unknownAppKeepsTheAppHero() {
        assertNull(AppSources.effective(focused, "org.videolan.vlc"))
    }

    @Test fun noFocusedAppUsesTheDefaultSource() {
        assertEquals(focused, AppSources.effective(focused, null))
    }

    @Test fun offIsOff() {
        assertNull(AppSources.effective(focused.copy(mode = FeaturedMode.Off), "com.netflix.ninja"))
        assertNull(AppSources.effective(FeaturedConfig(source = FeaturedSourceId.Off), null))
    }
}
