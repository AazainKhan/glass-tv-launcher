package dev.glasslauncher.data

import dev.glasslauncher.ui.Type
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

/** Settings › Display & Text › Font. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UiFontTest {
    private val json = Json { ignoreUnknownKeys = true }

    @After fun back() { Type.font = UiFont.Inter; Type.bold = false }

    @Test fun anOldConfigWithoutAFontKeepsInter() {
        val old = json.decodeFromString<LauncherConfig>("""{"boldText":true}""")
        assertEquals(UiFont.Inter, old.font)
    }

    @Test fun theChoiceSurvivesTheConfigRoundTrip() {
        val cfg = LauncherConfig(font = UiFont.Serif)
        assertEquals(UiFont.Serif, json.decodeFromString<LauncherConfig>(json.encodeToString(cfg)).font)
    }

    @Test fun aFontThisBuildDoesNotKnowKeepsTheRestOfTheConfig() {
        // The store's own Json: an unknown option (renamed or removed later) must not reset everything.
        val cfg = ConfigStore.json.decodeFromString<LauncherConfig>("""{"boldText":true,"textScale":1.2,"font":"Comic"}""")
        assertEquals(UiFont.Inter, cfg.font)
        assertEquals(true, cfg.boldText)
        assertEquals(1.2f, cfg.textScale, 0f)
    }

    @Test fun boldTextStillBoldensTheTvsOwnFaces() {
        Type.font = UiFont.System
        Type.bold = false
        val regular = Type.body.fontWeight
        Type.bold = true
        try { assertEquals(regular!!.weight + 100, Type.body.fontWeight!!.weight) } finally { Type.bold = false }
    }

    @Test fun everyFontDrawsWithItsOwnFamily() {
        val families = UiFont.entries.map { f -> Type.font = f; Type.body.fontFamily }
        assertEquals("one family per font: $families", UiFont.entries.size, families.distinct().size)
    }

    @Test fun titlesFollowTheFontToo() {
        Type.font = UiFont.Inter
        val inter = Type.title.fontFamily
        Type.font = UiFont.Serif
        assertNotEquals(inter, Type.title.fontFamily)
    }
}
