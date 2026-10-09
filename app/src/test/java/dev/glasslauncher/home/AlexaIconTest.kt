package dev.glasslauncher.home

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Control Center's Alexa button shows the installed Alexa app's icon, or nothing when it can't. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AlexaIconTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val pm: PackageManager = context.packageManager

    private fun install(enabled: Boolean = true, withIcon: Boolean = true) {
        val app = ApplicationInfo().apply {
            packageName = AlexaIcon.PACKAGE
            flags = ApplicationInfo.FLAG_INSTALLED
            this.enabled = enabled
        }
        shadowOf(pm).installPackage(PackageInfo().apply { packageName = AlexaIcon.PACKAGE; applicationInfo = app })
        if (withIcon) {
            val art = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(0, 160, 255)) }
            shadowOf(pm).setApplicationIcon(AlexaIcon.PACKAGE, BitmapDrawable(context.resources, art))
        }
        if (!enabled) pm.setApplicationEnabledSetting(AlexaIcon.PACKAGE, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)
    }

    @Test fun nullWhenAlexaIsNotInstalled() {
        assertNull(AlexaIcon.load(pm, 52))
    }

    @Test fun theInstalledAppsIconAtTheRequestedSize() {
        install()
        val icon = AlexaIcon.load(pm, 52)
        assertNotNull(icon)
        assertEquals(52, icon!!.width)
        assertEquals(52, icon.height)
        // Full colour, not a mask: the fake app's blue comes through.
        assertEquals(Color.rgb(0, 160, 255), icon.getPixel(26, 26))
    }

    @Test fun nullWhenAlexaIsDisabled() {
        install(enabled = false)
        assertNull(AlexaIcon.load(pm, 52))
    }

    @Test fun nullWhenTheIconCannotBeLoaded() {
        install(withIcon = false)
        // A corrupt or half-removed package: the drawable exists but drawing it fails.
        shadowOf(pm).setApplicationIcon(AlexaIcon.PACKAGE, object : Drawable() {
            override fun draw(canvas: Canvas) = throw IllegalStateException("corrupt icon")
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(filter: ColorFilter?) {}
            @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.OPAQUE
        })
        assertNull(AlexaIcon.load(pm, 52))
    }
}
