package dev.glasslauncher.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The app menu's glyphs are bitmaps made once (P204): warming makes every one, and a draw gets the same bitmap back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MenuIconsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun warmMakesEveryMenuGlyphAtTheDrawnSize() {
        MenuIcons.warm(context)
        val px = MenuIcons.sizePx(context)
        MenuIcons.ALL.forEach { icon ->
            val bitmap = MenuIcons.get(context, icon, px)
            assertNotNull("glyph $icon", bitmap)
            assertEquals(px, bitmap!!.width)
            assertSame("made once", bitmap, MenuIcons.get(context, icon, px))
        }
    }
}
