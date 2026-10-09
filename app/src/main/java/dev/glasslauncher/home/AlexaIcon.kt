package dev.glasslauncher.home

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable

/** The installed Alexa app's own logo (Amazon's artwork is a trademark, so none is bundled). */
object AlexaIcon {
    const val PACKAGE = "com.amazon.vizzini"

    /** Fire OS's Alexa ships Android's generic robot as its app icon; the blue Alexa circle is this drawable. */
    const val LOGO = "ic_hint_alexa_blue_48x48"

    /**
     * The logo drawn at [px] x [px], or null when the app isn't installed, is disabled, has no logo
     * (never the generic app icon) or it won't load.
     */
    fun load(pm: PackageManager, px: Int, find: (PackageManager, ApplicationInfo) -> Drawable? = ::logo): Bitmap? = try {
        val info = pm.getApplicationInfo(PACKAGE, 0)
        val icon = if (info.enabled) find(pm, info) else null
        icon?.let { Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b -> it.setBounds(0, 0, px, px); it.draw(Canvas(b)) } }
    } catch (e: Exception) {
        null
    }

    private fun logo(pm: PackageManager, info: ApplicationInfo): Drawable? {
        val res = pm.getResourcesForApplication(info)
        val id = res.getIdentifier(LOGO, "drawable", PACKAGE)
        return if (id == 0) null else res.getDrawable(id, null)
    }
}
