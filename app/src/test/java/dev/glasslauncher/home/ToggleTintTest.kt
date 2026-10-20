package dev.glasslauncher.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P219: an active glyph (Wi-Fi, Bluetooth, Speed…) keeps its accent colour when its tile has focus; focus is lift only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ToggleTintTest {
    @get:Rule val compose = createComposeRule()

    private val accent = Color(0xFF0A84FF)
    private val restInk = Color.White
    private val focusInk = Color(0xFF111111)

    private fun tint(on: Boolean, focused: Boolean): Color {
        var out = Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalCcTileFocused provides focused) {
                out = toggleTint(on, if (focused) focusInk else restInk, accent)
            }
        }
        compose.waitForIdle()
        return out
    }

    @Test fun anActiveGlyphIsTheAccentFocusedOrNot() {
        assertEquals(accent, tint(on = true, focused = false))
    }

    @Test fun anActiveGlyphStaysTheAccentWhenFocused() {
        assertEquals("not the focus ink", accent, tint(on = true, focused = true))
    }

    @Test fun anInactiveGlyphFollowsTheRestingInk() {
        assertEquals(restInk, tint(on = false, focused = false))
    }

    @Test fun anInactiveFocusedGlyphFollowsTheFocusInkSoItReadsOnTheFocusFill() {
        assertEquals(focusInk, tint(on = false, focused = true))
    }
}
