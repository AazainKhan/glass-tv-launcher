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
        assertEquals(FeaturedSourceId.Tmdb, c.source)
        assertEquals("netflix", c.tmdbProvider)
    }

    @Test fun serviceWithoutKeyFallsBackToDefault() {
        assertEquals(focused, AppSources.effective(focused, "com.amazon.firetv.youtube"))
    }

    @Test fun unknownAppFallsBack() {
        assertEquals(focused, AppSources.effective(focused, "org.videolan.vlc"))
    }

    @Test fun offIsOff() {
        assertNull(AppSources.effective(focused.copy(mode = FeaturedMode.Off), "com.netflix.ninja"))
        assertNull(AppSources.effective(FeaturedConfig(source = FeaturedSourceId.Off), null))
    }
}
