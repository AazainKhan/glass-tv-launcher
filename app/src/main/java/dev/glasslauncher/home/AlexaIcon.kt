package dev.glasslauncher.home

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas

/** The installed Alexa app's own launcher icon (Amazon's artwork is a trademark, so none is bundled). */
object AlexaIcon {
    const val PACKAGE = "com.amazon.vizzini"

    /** The icon drawn at [px] x [px], or null when the app isn't installed, is disabled or its icon won't load. */
    fun load(pm: PackageManager, px: Int): Bitmap? = try {
        val info = pm.getApplicationInfo(PACKAGE, 0)
        if (!info.enabled) null else {
            val icon = pm.getApplicationIcon(info)
            Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { icon.setBounds(0, 0, px, px); icon.draw(Canvas(it)) }
        }
    } catch (e: Exception) {
        null
    }
}
