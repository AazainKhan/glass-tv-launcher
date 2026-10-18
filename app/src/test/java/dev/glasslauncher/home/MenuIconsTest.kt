package dev.glasslauncher.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The app menu's glyphs are platform vector drawables, inflated ahead of time (P204); every draw gets its own copy to tint. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MenuIconsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun warmInflatesEveryMenuGlyphAndEachDrawGetsItsOwnCopy() {
        MenuIcons.warm(context)
        MenuIcons.ALL.forEach { icon ->
            val a = MenuIcons.drawable(context, icon)
            val b = MenuIcons.drawable(context, icon)
            assertNotNull("glyph $icon", a)
            assertNotSame("a copy per draw: the tint is state on the drawable", a, b)
        }
    }
}
