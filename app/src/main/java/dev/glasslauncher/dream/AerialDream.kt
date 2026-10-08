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

/** The view for the chosen screensaver: the photo slideshow, or Aerials. */
internal fun saverView(context: Context, slideshow: Boolean, nearEndMs: Long = 0L): android.view.View {
    val cfg = context.app.config.config.value.screensaver
    return if (slideshow) SlideshowView(context, cfg) else AerialView(context, cfg, nearEndMs)
}

private fun Context.slideshowChosen() = app.config.config.value.screensaverMode == dev.glasslauncher.data.ScreensaverMode.Slideshow

class AerialDreamService : DreamService() {
    private var view: Saver? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val v = saverView(this, slideshowChosen())
        setContentView(v)
        view = v as Saver
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
    private var view: Saver? = null

    companion object {
        const val EXTRA_NEAR_END_MS = "dev.glasslauncher.extra.NEAR_END_MS"
        /** Which screensaver to show (a preview); without it, the one chosen in Settings. */
        const val EXTRA_SLIDESHOW = "dev.glasslauncher.extra.SLIDESHOW"

        /** Starts the screensaver fading in over Home rather than cutting to black. */
        fun start(context: android.content.Context, slideshow: Boolean? = null) {
            val options = android.app.ActivityOptions.makeCustomAnimation(context, dev.glasslauncher.R.anim.glass_fade_in, dev.glasslauncher.R.anim.glass_hold)
            val intent = android.content.Intent(context, AerialActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (slideshow != null) intent.putExtra(EXTRA_SLIDESHOW, slideshow)
            runCatching { context.startActivity(intent, options.toBundle()) }
        }
    }

    override fun finish() {
        super.finish()
        // Fade out to Home instead of the system's slide.
        overridePendingTransition(dev.glasslauncher.R.anim.glass_hold, dev.glasslauncher.R.anim.glass_fade_out)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Device tests: start the first clip this many ms before its end, so a clip change can be checked
        // without waiting minutes for one (`am start … --el dev.glasslauncher.extra.NEAR_END_MS 6000`).
        val nearEnd = intent.getLongExtra(EXTRA_NEAR_END_MS, 0L)
        val slideshow = if (intent.hasExtra(EXTRA_SLIDESHOW)) intent.getBooleanExtra(EXTRA_SLIDESHOW, false) else slideshowChosen()
        val v = saverView(this, slideshow, nearEnd)
        setContentView(v)
        view = v as Saver
    }

    private val sleepAfter = Runnable { finish() }

    override fun onStart() {
        super.onStart()
        view?.start()
        // Keep the screen on only until the TV's own sleep timeout, so Aerials never stop it sleeping.
        val sleepMs = runCatching { android.provider.Settings.Secure.getLong(contentResolver, "sleep_timeout") }.getOrDefault(20 * 60_000L)
        window.decorView.postDelayed(sleepAfter, sleepMs.coerceIn(5 * 60_000L, 4 * 3600_000L))
    }

    override fun onStop() {
        window.decorView.removeCallbacks(sleepAfter)
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
