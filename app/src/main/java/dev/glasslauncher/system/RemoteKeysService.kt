package dev.glasslauncher.system

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.glasslauncher.MainActivity
import dev.glasslauncher.app
import kotlinx.coroutines.launch

/**
 * Remote buttons (Settings › Remote Buttons): runs the action picked for each button in
 * RemoteButtons, e.g. an app button opens any app, Recent Apps opens Glass's app switcher. Every other
 * key passes straight through. It also snapshots the app in front for the app switcher's blurred card
 * previews (AppPreviews); screen text and window content are never read.
 */
class RemoteKeysService : AccessibilityService() {

    /** Control Center as an overlay over any app (and over Home). */
    val controlCenter by lazy { dev.glasslauncher.home.ControlCenterWindow(this) }

    override fun onServiceConnected() {
        instance = this
        // A bound keys service is a second way to have the always-on remote scan armed (P100): a Home that was force-
        // stopped cancels the scan's PendingIntent, and nothing delivers to a stopped app until it starts again.
        if (RootFeatures.ownRemotePairing(this)) runCatching { RemotePairer.obtain(this).start() }
        // Some builds drop the XML flag; asking again at runtime turns key filtering on.
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Log.i(TAG, "connected, flags=${serviceInfo.flags} caps=${serviceInfo.capabilities}")
        // On by default (files/cc-cold turns it off): Control Center's window is attached parked so the first open is a resize.
        runCatching { controlCenter.warmUp() }
    }

    // ── Banner (Sleep Timer's one-minute warning) ─────────────────────────────────────────────────

    private var banner: android.view.View? = null
    private val dropBanner = Runnable { hideBanner(animate = true) }

    /**
     * Glass's toast (P105 #13): a capsule at the status pill's spot, top right, over any app (an accessibility overlay:
     * never takes focus or touches). It scales from 0.9 and fades in over 130 ms and leaves the same way, with a soft
     * 1 dp rim, not the hard stroke and the one-frame pop the Sleep Timer banner had; replaces Android's grey toast.
     */
    fun showBanner(text: String, forMs: Long) {
        handler.post {
            hideBanner(animate = false)
            val dp = resources.displayMetrics.density
            val view = android.widget.TextView(this).apply {
                this.text = text
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                setPadding((22 * dp).toInt(), (12 * dp).toInt(), (22 * dp).toInt(), (12 * dp).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xE61C1C1E.toInt()); cornerRadius = 40 * dp
                    setStroke((1 * dp).toInt(), 0x1AFFFFFF)
                }
                alpha = 0f; scaleX = 0.9f; scaleY = 0.9f
                // Grows out of its top-right corner, where the status pill is.
                pivotX = 1_000f; pivotY = 0f
            }
            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                // Right edge in line with Home's status pill (22 dp in, 22–54 dp down) and just under it, so on Home the
                // two capsules stack instead of overlapping.
                gravity = android.view.Gravity.TOP or android.view.Gravity.END; x = (22 * dp).toInt(); y = (60 * dp).toInt()
            }
            runCatching {
                (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).addView(view, params)
                banner = view
                view.post { view.pivotX = view.width.toFloat(); view.pivotY = 0f }
                view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(130)
                    .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)).start()
            }
            handler.postDelayed(dropBanner, forMs)
        }
    }

    private fun hideBanner(animate: Boolean = true) {
        handler.removeCallbacks(dropBanner)
        val v = banner ?: return
        banner = null
        val remove = { runCatching { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(v) } }
        if (!animate) { remove(); return }
        v.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(130).withEndAction { remove() }.start()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // A key from anything but the remote (CEC, a phone app, a controller) says the user is at the TV: the
        // fresh-batteries hint is allowed for a while after it (P98), never on a plain idle Home.
        if (event.action == KeyEvent.ACTION_DOWN) RemoteWatch.noteKey(event.device?.name)
        // The Sleep Timer's one-minute banner is up: any key cancels the timer (the key still reaches the app).
        if (event.action == KeyEvent.ACTION_DOWN && SleepTimer.warning(this)) {
            SleepTimer.set(this, 0)
            showBanner("Sleep timer cancelled", 2_000)
        }
        // "You can always back out" (P87): Back or Home while the app Glass just started hasn't drawn a window yet
        // cancels it and returns Home. The gate closes the moment any app window appears, so Back inside an app is
        // never taken. Both halves of the press are swallowed.
        if ((event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_HOME) && app.config.config.value.instantBack) {
            val now = android.os.SystemClock.uptimeMillis()
            // The system holds Back until a window of the app has INPUT FOCUS (a starting window and a resumed
            // activity both come earlier). So the app owns Back only once a focused application window of another
            // package exists; until then Back is Glass's to take (P87).
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && LaunchGate.interruptible(now) &&
                LaunchGate.shouldInterrupt(now, appHasFocus())) {
                Log.i(TAG, "instant back: ${LaunchGate.launching(now)} had no focused window yet")
                LaunchGate.clear()
                interrupted = true
                performGlobalAction(GLOBAL_ACTION_HOME)
                return true
            }
            if (interrupted && event.action == KeyEvent.ACTION_UP) { interrupted = false; return true }
            if (interrupted) return true
        }
        // Escape hatch: holding Back for 1.5 s goes Home from any app, even one that swallows Back, Home
        // and Recents (Fire TV Early Access did). The press itself still reaches the app. Timed from the
        // first down, not from key repeats: the system makes those after accessibility services have seen
        // the key, so a held Back reaches this service as just a down and, later, an up.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                escaped = false
                handler.removeCallbacks(escape)
                handler.postDelayed(escape, ESCAPE_HOLD_MS)
            }
            if (escaped) return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(escape)
            val was = escaped
            escaped = false
            if (was) return true
        }
        val name = KeyEvent.keyCodeToString(event.keyCode)
        val action = RemoteButtons.actionFor(name, app.config.config.value.remoteButtons, event.scanCode) ?: return false
        if (dev.glasslauncher.BuildConfig.DEBUG && event.action == KeyEvent.ACTION_DOWN) Log.i(TAG, "$name scan=${event.scanCode} -> $action")
        // Act on the first down (a key the layout doesn't name never delivers its up here); ignore
        // auto-repeat, and swallow every half of the press.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) run(action)
        return true
    }

    /** Whether a focused application window of another package exists (the launched app can take Back). */
    private fun appHasFocus(): Boolean {
        val own = packageName
        // Can't see windows (capability off: the system then answers an empty list, not an error): assume the app has
        // focus, so Back is never taken.
        val windows = runCatching { windows }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return true
        val focused = FocusPick.focusedAppPackage(windows.map { w ->
            FocusPick.Win(w.isFocused, w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION, runCatching { w.root?.packageName?.toString() }.getOrNull())
        })
        return focused != null && focused != own
    }

    private var escaped = false
    /** A Back/Home press was taken as "cancel the launch": its remaining events are swallowed too. */
    private var interrupted = false
    private val escape = Runnable {
        escaped = true
        runCatching { startActivity(HomeSetup.homeIntent(this)) }
    }

    private fun run(action: RemoteAction) {
        when (action) {
            is RemoteAction.OpenApp -> {
                val pm = packageManager
                // Not installed (e.g. Amazon Music after a debloat): its Appstore page, as Fire TV does.
                val intent = pm.getLeanbackLaunchIntentForPackage(action.pkg) ?: pm.getLaunchIntentForPackage(action.pkg)
                    ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("amzn://apps/android?p=${action.pkg}"))
                runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            RemoteAction.ControlCenter -> {
                // Always logged (P50: a first press after an install was lost once and never reproduced).
                Log.i(TAG, "Settings key: Control Center ${if (controlCenter.showing) "close" else "open"} (front=${front ?: "Glass"})")
                controlCenter.toggle()
            }
            RemoteAction.AppSwitcher -> home(MainActivity.ACTION_APP_SWITCHER)
            RemoteAction.TvSettings -> home(MainActivity.ACTION_TV_SETTINGS)
            RemoteAction.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            RemoteAction.Nothing, RemoteAction.Default -> Unit
        }
    }

    private fun home(action: String) {
        startActivity(
            Intent(this, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    // ── App switcher previews ──────────────────────────────────────────────────────────────────────
    // A snapshot of the app in front, 3 s after it arrives and every 30 s while it stays (AppPreviews).

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var front: String? = null
    /** The app in front (null when it's Glass), for Control Center's glass. */
    val frontApp: String? get() = front
    private val launchable = HashMap<String, Boolean>()

    private val capture = object : Runnable {
        override fun run() {
            val pkg = front ?: return
            val power = getSystemService(android.os.PowerManager::class.java)
            // Not while Control Center is over the app: the preview would show it instead of the app.
            if (android.os.Build.VERSION.SDK_INT >= 30 && power?.isInteractive == true && !controlCenter.showing) {
                runCatching {
                    takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            buffer.close()
                            // Still the same app (a screenshot takes a frame or two).
                            if (bitmap != null && front == pkg) AppPreviews.save(this@RemoteKeysService, pkg, bitmap) else bitmap?.recycle()
                        }
                        override fun onFailure(errorCode: Int) = Unit
                    })
                }
            }
            handler.postDelayed(this, PREVIEW_EVERY_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (dev.glasslauncher.BuildConfig.DEBUG && pkg != packageName) Log.i(TAG, "window state: $pkg (gate launching=${LaunchGate.launching(android.os.SystemClock.uptimeMillis())})")
        // Glass's own windows other than Home (the Control Center overlay itself) aren't an app change.
        val glassHome = pkg == packageName && event.className?.toString() == MainActivity::class.java.name
        if (pkg == packageName && !glassHome) return
        if (pkg != front || glassHome) controlCenter.onAppChanged()
        if (pkg == front) return
        // Only apps with a launcher entry count; dialogs, the keyboard and system overlays don't change "front".
        val isApp = launchable.getOrPut(pkg) {
            pkg != packageName && (packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)) != null
        }
        if (!isApp && pkg != packageName) return
        front = pkg.takeIf { isApp }
        handler.removeCallbacks(capture)
        if (front != null) handler.postDelayed(capture, PREVIEW_FIRST_MS)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // Only a live process sees this: a user's switch-off in Accessibility settings. KeysService reads it to tell that from a force-stop.
        getSharedPreferences("keysService", 0).edit().putLong("unboundAt", System.currentTimeMillis()).apply()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        controlCenter.hide()
        controlCenter.release()
        handler.removeCallbacks(capture)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    companion object {
        /** The connected service, for Glass to open Control Center as an overlay. */
        @Volatile var instance: RemoteKeysService? = null
            private set
        private const val PREVIEW_FIRST_MS = 3_000L
        private const val PREVIEW_EVERY_MS = 30_000L
        const val ESCAPE_HOLD_MS = 1_500L
        const val TAG = "GlassRemote"
    }
}
