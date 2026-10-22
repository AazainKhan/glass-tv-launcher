package dev.glasslauncher.glass

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P257: scenes bake no Control Center sheet under the default variant; another variant gets one on demand. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackdropSheetTest {
    private fun scene(): Backdrop {
        val art = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 80, 160)) }.asImageBitmap()
        return Backdrop(sharp = art, blurred = art, ladder = listOf(art), blurredSoftware = art, clearSoftware = art, isLight = false)
    }

    @Test fun theDefaultVariantHasNoSheet() = assertNull(scene().sheetFor("E"))

    @Test fun anotherVariantBakesTheSheetOnceOnDemand() {
        val b = scene()
        val sheet = b.sheetFor("A")
        assertNotNull("a scene baked under E still gets a sheet when the variant changes", sheet)
        assertSame("and keeps it", sheet, b.sheetFor("A"))
    }
}
