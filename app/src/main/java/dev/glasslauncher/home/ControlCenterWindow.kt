package dev.glasslauncher.home

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.layout.onSizeChanged
import dev.glasslauncher.MainActivity
import dev.glasslauncher.app
import dev.glasslauncher.data.ThemeMode
import dev.glasslauncher.glass.BackdropState
import dev.glasslauncher.glass.LocalBackdrop
import kotlinx.coroutines.launch

/**
 * Control Center over any app (tvOS opens it over whatever is playing): an accessibility overlay
 * window owned by RemoteKeysService. No extra permission, nothing behind is paused, and the dark wash
 * behind the tiles is composited by the system without redrawing the app (or Home) underneath.
 */
class ControlCenterWindow(private val service: AccessibilityService) : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val windows = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: ComposeView? = null
    private var cached: ComposeView? = null
    private var exiting by mutableStateOf(false)
    private var shownAt = 0L
    /**
     * The overlay can't sample the screen behind it, so each open captures it (the accessibility
     * screenshot, ~100 ms) and bakes it into the same clear-glass textures as the tray. Until then (or
     * without the capture) the tiles draw a smoky translucent tint over the live app.
     */
    private val backdrop = BackdropState().apply { translucentWindow = true; sceneDim = CC_DIM_ALPHA }
    /** The scene dim for the appearance in use (P105 #14): light mutes less. */
    private fun dimNow(light: Boolean) = if (light) dev.glasslauncher.glass.GlassMatch.CC_DIM_LIGHT else CC_DIM_ALPHA
    private var capture: kotlinx.coroutines.Job? = null
    /** Open over Home: the glass follows Home's scene (a slide can change under Control Center). */
    private var overHome by mutableStateOf(false)
    private val app get() = service.app

    /** An open asked for and waiting on the screen capture (up to [CAPTURE_WAIT_MS]): the window isn't attached yet, but a close must still win. */
    private var pending = false

    /**
     * Kept warm (P111 d, default on): after the first open the window is not removed but parked, 1x1
     * and untouchable, its composition live in its "closed" state, so the next open resizes it instead of adding a new
     * accessibility window (about 80% of the 130 ms key-to-first-frame in Yin's trace was that, plus a cold first
     * composition). While parked: [view] is set, [exiting] is true, [warm] is true, the lifecycle is CREATED.
     */
    private var warm = false

    /**
     * Whether to keep the window warm. Parked 1x1 by default: on the stick it cut the first frame from 112–143 ms to
     * about 80 ms for no measurable PSS. The marker file `files/cc-cold` turns it off (a new window per open, as
     * before). `files/cc-warm-full` parks it at full size, fully transparent, so an open only flips alpha and input
     * (about 53 ms); it stays off because the full-screen surface cost about +5.7 MB PSS, over the +3 MB allowed.
     */
    private fun warmWanted(): Boolean = warmFull() || !java.io.File(service.filesDir, "cc-cold").exists()
    private fun warmFull(): Boolean = java.io.File(service.filesDir, "cc-warm-full").exists()

    private fun fullParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply { title = "Glass Control Center" }

    private fun parkedFlags() = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private fun composeView(): ComposeView = cached ?: ComposeView(service).apply {
        setViewTreeLifecycleOwner(this@ControlCenterWindow)
        setViewTreeSavedStateRegistryOwner(this@ControlCenterWindow)
        setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@ControlCenterWindow))
        setContent { Host() }
    }.also { cached = it }

    /** Parks the attached window (1x1, untouchable) instead of removing it. */
    private fun park(v: android.view.View) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        applyParked(lp)
        runCatching { windows.updateViewLayout(v, lp) }
        warm = true
    }

    private fun applyParked(lp: WindowManager.LayoutParams) {
        if (warmFull()) { lp.width = WindowManager.LayoutParams.MATCH_PARENT; lp.height = WindowManager.LayoutParams.MATCH_PARENT; lp.alpha = 0f }
        else { lp.width = 1; lp.height = 1; lp.alpha = 1f }
        lp.flags = parkedFlags()
    }

    /** Warms the window up when the flag is on: attaches it parked, once, so the first open is only a resize. */
    fun warmUp() {
        if (!warmWanted() || view != null || pending) return
        val v = composeView()
        exiting = true
        val lp = fullParams().also { applyParked(it) }
        runCatching { windows.addView(v, lp) }.onFailure { android.util.Log.w("ControlCenterWindow", "warmUp: addView failed", it); exiting = false; return }
        view = v
        warm = true
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        android.util.Log.i("ControlCenterWindow", "warmUp: parked")
    }

    /** The service is going: the window goes with it, parked or not. */
    fun release() {
        val v = view ?: return
        runCatching { windows.removeView(v) }
        view = null; warm = false; exiting = false; open = false; pillCovered = false; pending = false
    }

    val showing: Boolean get() = (view != null || pending) && !exiting

    companion object {
        /** True while the overlay is up, so Home hides its status pill (Control Center has its own clock). */
        var open by mutableStateOf(false)
            private set

        /** Home's baked scene while Home is on screen: Control Center's glass over Home samples it directly. */
        private const val CAPTURE_WAIT_MS = 120L
        /** The latest an over-app open attaches (from show), however long the capture and its bake take. */
        private const val ATTACH_CAP_MS = 280L

        /** Set by Control Center while its edit mode is on: returns true when it used the Back press. */
        @Volatile var editBack: (() -> Boolean)? = null
        var homeBackdrop by mutableStateOf<dev.glasslauncher.glass.Backdrop?>(null)

        /** Whether Glass's activity is started (Home is on screen). Lifecycle-driven, so it is right while an app hides Home and no frame runs. */
        @Volatile var homeStarted = true

        /**
         * The glass Control Center samples for this open: Home's own scene only when Glass is the front app (null
         * [frontPkg]) and its activity is started; otherwise null, and the caller captures the app on screen.
         */
        fun sceneFor(frontPkg: String?, glassStarted: Boolean, homeBackdrop: dev.glasslauncher.glass.Backdrop?): dev.glasslauncher.glass.Backdrop? =
            if (frontPkg == null && glassStarted) homeBackdrop else null

        /**
         * Home's lifecycle hook: [homeBackdrop] is cleared on STOP and restored (from [scene]) on START, off the
         * frame clock (a stopped Home composes nothing, so an effect there can't clear it).
         */
        fun homeLifecycleObserver(scene: () -> dev.glasslauncher.glass.Backdrop?) = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { homeStarted = false; homeBackdrop = null }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { homeStarted = true; homeBackdrop = scene() }
                else -> Unit
            }
        }

        /**
         * True while Control Center's capsule covers Home's status pill: set by Control Center (either path) on its
         * first frame drawn with its material, cleared when a close lands, before the window or overlay goes.
         */
        var pillCovered by mutableStateOf(false)

        /**
         * P238: Control Center is drawing once, unseen, so its shader programs compile and its display lists record before the
         * first real open after an install or update (that open compiled ~16 programs, ~380 ms). While true it takes no
         * focus and leaves Home's pill alone.
         */
        @Volatile var warmingUp = false

        /** Whether Home's pill has focus (white), so Control Center's first frame draws it the same. */
        @Volatile var pillFocused = false

        /** Where Home's status pill is, so Control Center can grow out of it (Home keeps this current). */
        @Volatile var pillBounds: androidx.compose.ui.geometry.Rect? = null
        /** P207: the status chrome (search circle and pill), in window pixels, so Control Center can hide them in Home's capture. */
        @Volatile var chromeBounds: androidx.compose.ui.geometry.Rect? = null
    }

    init {
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /**
     * P238: draws the parked window once in its open state at an alpha that rounds to nothing (and taking no input), over
     * Home only, then parks it again. [done] runs after it is parked.
     */
    fun warmShaders(done: (ok: Boolean) -> Unit) {
        val v = view
        val home = homeBackdrop
        if (!warm || v == null || home == null || open || pending) { done(false); return }
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: run { done(false); return }
        warmingUp = true
        overHome = true
        app.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { backdrop.swap(home, animate = false) }
        lp.width = WindowManager.LayoutParams.MATCH_PARENT; lp.height = WindowManager.LayoutParams.MATCH_PARENT
        lp.alpha = WARM_ALPHA; lp.flags = parkedFlags()
        runCatching { windows.updateViewLayout(v, lp) }
        exiting = false
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        // Done once the window has really drawn its open for a while, not after a fixed time: under load (keys, a busy
        // GPU) a fixed 450 ms drew too few frames and left programs uncompiled (P238 review).
        var draws = 0
        val counter = android.view.ViewTreeObserver.OnDrawListener { draws++ }
        v.viewTreeObserver.addOnDrawListener(counter)
        val started = android.os.SystemClock.uptimeMillis()
        // A parked Control Center is static: left alone the window drew 4-5 frames a second on a quiet stick, so the frames
        // are driven, one invalidate per vsync, while the warm-up lasts (P238 review).
        var driving = true
        val choreographer = android.view.Choreographer.getInstance()
        val drive = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) { if (!driving) return; v.invalidate(); choreographer.postFrameCallback(this) }
        }
        choreographer.postFrameCallback(drive)
        fun finish(ok: Boolean) {
            driving = false
            handler.post { runCatching { v.viewTreeObserver.removeOnDrawListener(counter) } }
            exiting = true
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            if (view === v && !open) { applyParked(lp); runCatching { windows.updateViewLayout(v, lp) }; overHome = false; backdrop.clear() }
            warmingUp = false
            android.util.Log.i("ControlCenterWindow", "warmShaders ok=$ok draws=$draws in ${android.os.SystemClock.uptimeMillis() - started} ms")
            done(ok)
        }
        val check = object : Runnable {
            override fun run() {
                // A real open took over: stop warming, and say so (it compiled the programs itself).
                if (open || view !== v) { driving = false; warmingUp = false; done(false); return }
                val elapsed = android.os.SystemClock.uptimeMillis() - started
                if (draws >= WARM_DRAWS && elapsed >= WARM_MS) finish(true)
                else if (elapsed >= WARM_MAX_MS) finish(false)
                else handler.postDelayed(this, 50L)
            }
        }
        handler.postDelayed(check, WARM_MS)
    }

    fun toggle() = if (showing) hide() else show()

    fun show() {
        // Opening Control Center is an interaction: it cancels a Sleep Timer that is showing its banner.
        dev.glasslauncher.system.SleepTimer.cancelOnInteraction(service)
        if (view != null && !warm) {
            if (exiting) { exiting = false; open = true; removal?.let(handler::removeCallbacks); takesInput(true) }
            android.util.Log.i("ControlCenterWindow", "show: already attached (exiting=$exiting)")
            return
        }
        if (pending) { android.util.Log.i("ControlCenterWindow", "show: an open is already pending"); return }
        // A parked window stays "closed" until its material is in place (attach), so it doesn't start opening bare.
        if (!warm) exiting = false
        pending = true
        shownAt = android.os.SystemClock.uptimeMillis()
        // Built once and kept: its composition survives the window being removed, so a second open only
        // re-attaches it (composing Control Center from scratch cost the slowest frames of opening).
        val v = composeView()
        val params = fullParams()
        fun attach() {
            // A hide() while the capture was pending withdrew the open.
            if (!pending || (view != null && !warm)) return
            pending = false
            if (warm) {
                // The parked window grows to the screen and takes input again: no new accessibility window.
                val lp = v.layoutParams as? WindowManager.LayoutParams
                if (lp != null) { lp.width = WindowManager.LayoutParams.MATCH_PARENT; lp.height = WindowManager.LayoutParams.MATCH_PARENT; lp.alpha = 1f; lp.flags = params.flags }
                runCatching { windows.updateViewLayout(v, lp) }.onFailure { android.util.Log.w("ControlCenterWindow", "show: resize failed", it); return }
                warm = false
                exiting = false
            } else {
                runCatching { windows.addView(v, params) }.onFailure { android.util.Log.w("ControlCenterWindow", "show: addView failed", it); return }
                view = v
            }
            open = true
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
        val front = (service as? dev.glasslauncher.system.RemoteKeysService)?.frontApp
        val home = sceneFor(front, homeStarted, homeBackdrop)
        overHome = home != null
        backdrop.sceneDim = dimNow(lightNow())
        if (home != null) {
            app.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { backdrop.swap(home, animate = false) }
            attach()
        } else captureThenAttach(::attach)
    }

    /** Counts over-app opens, so a stale timer can tell it belongs to an earlier one. */
    private var shows = 0

    /** The pending window removal of a close, cancelled if Control Center re-opens meanwhile. */
    private var removal: Runnable? = null

    /** Whether the window takes focus and touches; off from the moment a close starts, so Select/D-pad reach the app below. */
    private fun takesInput(on: Boolean) {
        val v = view ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val off = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        lp.flags = if (on) lp.flags and off.inv() else lp.flags or off
        runCatching { windows.updateViewLayout(v, lp) }
    }

    /** Plays the exit (the same curve as the entrance), then removes the window. */
    fun hide() {
        pending = false
        val v = view ?: return
        if (exiting) return
        exiting = true
        takesInput(false)
        val remove = Runnable {
            if (view === v && exiting) {
                if (warmWanted()) {
                    // Kept warm: parked, still "closed" (exiting stays true), composition alive.
                    park(v)
                } else {
                    runCatching { windows.removeView(v) }.onFailure { android.util.Log.w("ControlCenterWindow", "removeView failed; window state cleared anyway", it) }
                    view = null
                    exiting = false
                }
                capture?.cancel()
                overHome = false
                backdrop.clear()
                // Home's pill comes back as the capsule lands on it, not while it's still shrinking.
                open = false
                pillCovered = false
                lifecycleRegistry.currentState = Lifecycle.State.CREATED
            }
        }
        removal = remove
        handler.postDelayed(remove, CcMorph.CLOSE_MS + 20L)
    }

    private fun apply(baked: dev.glasslauncher.glass.Backdrop) {
        if (view == null || exiting) return
        app.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { backdrop.swap(baked, animate = false) }
    }

    /**
     * Puts a baked capture in place: before the window attaches (still [pending]) it goes straight in, so Control
     * Center's first frame already has its sheet; after, only if the open has nothing yet.
     */
    private suspend fun install(baked: dev.glasslauncher.glass.Backdrop) {
        if (view == null) { if (pending) backdrop.swap(baked, animate = false) }
        else if (backdrop.backdrop == null) apply(baked)
    }

    /** The appearance in use now (the theme setting, or the system's when it follows it): its sheet is baked for it. */
    private fun lightNow(): Boolean = when (app.config.config.value.theme) {
        ThemeMode.Dark -> false
        ThemeMode.Light -> true
        ThemeMode.System -> (service.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * The app's last preview (already on the CPU, no readback), when a fresh capture isn't possible: baked (with
     * Control Center's sheet) and put in place, then [then] (the attach).
     */
    private fun usePreview(then: () -> Unit = {}) {
        val pkg = (service as? dev.glasslauncher.system.RemoteKeysService)?.frontApp ?: return then()
        capture?.cancel()
        capture = app.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val baked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                dev.glasslauncher.system.AppPreviews.load(service, pkg)?.let { runCatching { app.wallpapers.glassOnly(it, lightNow()) }.getOrNull() }
            }
            baked?.let { install(it) }
            then()
        }
    }

    /**
     * Over another app: capture the screen, copy it off the GPU and bake it (the clear texture and Control
     * Center's sheet, off the main thread) before the window appears, so the open draws the real glass from its
     * first frame and does no work of its own. The copy runs on the render thread, so made during the opening it
     * cost frames (perf: 20% janky vs 6%). The capture gets at most [CAPTURE_WAIT_MS]; past that, or if it fails
     * (rate-limited to one a second, shared with the app switcher's previews), the app's last preview stands in.
     * The window attaches by [ATTACH_CAP_MS] whatever happens (then with the flat material for that open).
     */
    private fun captureThenAttach(attach: () -> Unit) {
        var attached = false
        var shotIn = false
        // Timers of an earlier show (closed and re-opened quickly) must not attach this one early.
        val token = ++shows
        val go = { if (!attached && token == shows) { attached = true; attach() } }
        if (android.os.Build.VERSION.SDK_INT < 30) { go(); return }
        handler.postDelayed({ if (!attached && !shotIn) usePreview(then = go) }, CAPTURE_WAIT_MS)
        handler.postDelayed({ go() }, ATTACH_CAP_MS)
        val ok = runCatching {
            service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val shot = android.graphics.Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    buffer.close()
                    if (shot == null || attached || shotIn) { shot?.recycle(); return }
                    shotIn = true
                    val soft = shot.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    shot.recycle()
                    capture?.cancel()
                    capture = app.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        val baked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching { app.wallpapers.glassOnly(soft, lightNow()) }.getOrNull().also { soft.recycle() }
                        }
                        baked?.let { install(it) }
                        go()
                    }
                }
                override fun onFailure(errorCode: Int) { if (!shotIn) { shotIn = true; usePreview(then = go) } }
            })
        }.isSuccess
        if (!ok) { shotIn = true; usePreview(then = go) }
    }

    /** Another app (or Home) came forward: Control Center belongs to what was on screen when it opened. */
    fun onAppChanged() {
        if (showing && android.os.SystemClock.uptimeMillis() - shownAt > 400) hide()
    }

    /** Glass's own screens (TV Settings, Launcher Settings, the app switcher) open in Glass. */
    private fun openInGlass(overlay: Overlay) {
        val action = when (overlay) {
            Overlay.TvSettings -> MainActivity.ACTION_TV_SETTINGS
            Overlay.Settings -> MainActivity.ACTION_SETTINGS
            Overlay.AppSwitcher -> MainActivity.ACTION_APP_SWITCHER
            Overlay.Remotes -> MainActivity.ACTION_REMOTES
            Overlay.Downloads -> MainActivity.ACTION_DOWNLOADS
            else -> return
        }
        hide()
        runCatching {
            service.startActivity(
                Intent(service, MainActivity::class.java).setAction(action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    /**
     * The overlay's semantics tree in uiautomator's XML format, for the device tests: uiautomator only
     * dumps the active activity window and never sees accessibility overlays. Null when not showing.
     */
    fun dumpXml(): String? {
        val root = (view?.getChildAt(0) as? androidx.compose.ui.node.RootForTest) ?: return null
        if (!showing) return null
        return dev.glasslauncher.system.SemanticsDump.xml(root)
    }

    @androidx.compose.runtime.Composable
    private fun Host() {
        val context = LocalContext.current
        val app = context.app
        val cfg by app.config.config.collectAsStateWithLifecycle()
        val dark = when (cfg.theme) {
            ThemeMode.Dark -> true
            ThemeMode.Light -> false
            ThemeMode.System -> isSystemInDarkTheme()
        }
        val prefs = remember(cfg) { dev.glasslauncher.ui.UiPrefs.resolve(context, cfg) }
        val metrics = remember(cfg.textScale) { dev.glasslauncher.ui.Metrics(cfg.textScale) }
        val palette = remember(dark, prefs) { dev.glasslauncher.ui.Palette(light = !dark, highContrast = prefs.highContrast) }
        val density = LocalDensity.current
        val homeScene = homeBackdrop
        LaunchedEffect(homeScene, overHome) { if (overHome && homeScene != null && backdrop.backdrop !== homeScene) backdrop.swap(homeScene, animate = false) }
        dev.glasslauncher.ui.Type.bold = cfg.boldText
        dev.glasslauncher.ui.Type.font = cfg.font
        CompositionLocalProvider(
            LocalBackdrop provides backdrop,
            dev.glasslauncher.ui.LocalPalette provides palette,
            dev.glasslauncher.ui.LocalUiPrefs provides prefs,
            dev.glasslauncher.ui.LocalMetrics provides metrics,
            LocalDensity provides Density(density.density, density.fontScale * cfg.textScale),
            LocalOverlayExiting provides exiting,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .semantics { testTagsAsResourceId = true }
                    .testTag("overlay-top:ControlCenter")
                    // Glass maps the captured screen onto itself by its position in this full-screen window.
                    .onSizeChanged { backdrop.rootSize = it }
                    .onPreviewKeyEvent { e ->
                        val k = e.nativeKeyEvent
                        if (k.keyCode == KeyEvent.KEYCODE_BACK || k.keyCode == KeyEvent.KEYCODE_HOME) {
                            // Edit mode takes Back itself (cancel a lift, then save and leave edit mode); Home always closes.
                            if (k.action == KeyEvent.ACTION_UP) {
                                if (k.keyCode == KeyEvent.KEYCODE_BACK && editBack?.invoke() == true) Unit else hide()
                            }
                            true
                        } else false
                    },
            ) {
                ControlCenter(
                    edit = { t -> app.scope.launch { app.config.update(t) } },
                    cfg = cfg,
                    active = !exiting,
                    open = ::openInGlass,
                    closeAll = ::hide,
                )
            }
        }
    }
}

/** Half a level of white in an 8-bit frame: nothing on screen, but the window is composited and drawn. */
private const val WARM_ALPHA = 0.002f
/**
 * At least this long (the open's spring reaches full size, so the glass, rims and labels all draw) and this many frames
 * really drawn. Few: a frame that compiles is slow, and slow frames are the point.
 */
private const val WARM_MS = 450L
private const val WARM_DRAWS = 4
/** Gives up (and Home tries again later) when the frames don't come. */
private const val WARM_MAX_MS = 2_500L
