package dev.glasslauncher.dream

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.dreams.DreamService
import android.view.KeyEvent
import android.view.WindowManager
import dev.glasslauncher.app

class AerialDreamService : DreamService() {
    private var view: AerialView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        view = AerialView(this, app.config.config.value.screensaver).also { setContentView(it) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        view?.start()
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        view?.stop()
        view = null
        super.onDetachedFromWindow()
    }
}

/** Plays the screensaver on demand (preview, or the launcher's own idle fallback). Any key exits. */
class AerialActivity : Activity() {
    private var view: AerialView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = AerialView(this, app.config.config.value.screensaver).also { setContentView(it) }
    }

    override fun onStart() {
        super.onStart()
        view?.start()
    }

    override fun onStop() {
        view?.stop()
        super.onStop()
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }
}

object Screensaver {
    fun component(context: Context) = ComponentName(context, AerialDreamService::class.java)

    fun canWriteSecureSettings(context: Context) =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isSystemScreensaver(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, "screensaver_components")
            ?.split(',')?.any { ComponentName.unflattenFromString(it) == component(context) } == true

    /** Needs WRITE_SECURE_SETTINGS (granted once over adb). Returns false if not permitted. */
    fun setAsSystemScreensaver(context: Context): Boolean = runCatching {
        val cr = context.contentResolver
        Settings.Secure.putString(cr, "screensaver_components", component(context).flattenToString())
        Settings.Secure.putInt(cr, "screensaver_enabled", 1)
        Settings.Secure.putInt(cr, "screensaver_activate_on_sleep", 0)
        true
    }.getOrDefault(false)

    const val GRANT_COMMAND = "adb shell pm grant dev.glasslauncher android.permission.WRITE_SECURE_SETTINGS"
}
