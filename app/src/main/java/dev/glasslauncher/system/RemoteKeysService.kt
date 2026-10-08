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
        // Some builds drop the XML flag; asking again at runtime turns key filtering on.
        serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS }
        Log.i(TAG, "connected, flags=${serviceInfo.flags} caps=${serviceInfo.capabilities}")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Escape hatch: holding Back for 1.5 s goes Home from any app, even one that swallows Back, Home
        // and Recents (Fire TV Early Access did). The press itself still reaches the app.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 &&
            event.eventTime - event.downTime >= ESCAPE_HOLD_MS && !escaped) {
            escaped = true
            runCatching { startActivity(HomeSetup.homeIntent(this)) }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
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

    private var escaped = false

    private fun run(action: RemoteAction) {
        when (action) {
            is RemoteAction.OpenApp -> {
                val pm = packageManager
                // Not installed (e.g. Amazon Music after a debloat): its Appstore page, as Fire TV does.
                val intent = pm.getLeanbackLaunchIntentForPackage(action.pkg) ?: pm.getLaunchIntentForPackage(action.pkg)
                    ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("amzn://apps/android?p=${action.pkg}"))
                runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            RemoteAction.ControlCenter -> controlCenter.toggle()
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

    override fun onDestroy() {
        if (instance === this) instance = null
        controlCenter.hide()
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
