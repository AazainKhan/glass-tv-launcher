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
    private var capture: kotlinx.coroutines.Job? = null
    /** Open over Home: the glass follows Home's scene (a slide can change under Control Center). */
    private var overHome by mutableStateOf(false)
    private val app get() = service.app

    val showing: Boolean get() = view != null && !exiting

    companion object {
        /** True while the overlay is up, so Home hides its status pill (Control Center has its own clock). */
        var open by mutableStateOf(false)
            private set

        /** Home's baked scene while Home is on screen: Control Center's glass over Home samples it directly. */
        private const val CAPTURE_WAIT_MS = 120L

        var homeBackdrop by mutableStateOf<dev.glasslauncher.glass.Backdrop?>(null)

        /** Where Home's status pill is, so Control Center can grow out of it (Home keeps this current). */
        @Volatile var pillBounds: androidx.compose.ui.geometry.Rect? = null
    }

    init {
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun toggle() = if (showing) hide() else show()

    fun show() {
        if (view != null) { if (exiting) { exiting = false; open = true }; return }
        exiting = false
        shownAt = android.os.SystemClock.uptimeMillis()
        // Built once and kept: its composition survives the window being removed, so a second open only
        // re-attaches it (composing Control Center from scratch cost the slowest frames of opening).
        val v = cached ?: ComposeView(service).apply {
            setViewTreeLifecycleOwner(this@ControlCenterWindow)
            setViewTreeSavedStateRegistryOwner(this@ControlCenterWindow)
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@ControlCenterWindow))
            setContent { Host() }
        }.also { cached = it }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { title = "Glass Control Center" }
        fun attach() {
            if (view != null) return
            runCatching { windows.addView(v, params) }.onFailure { return }
            view = v
            open = true
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
        val home = homeBackdrop
        overHome = home != null
        if (home != null) {
            app.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { backdrop.swap(home, animate = false) }
            attach()
        } else captureThenAttach(::attach)
    }

    /** Plays the exit (the same curve as the entrance), then removes the window. */
    fun hide() {
        val v = view ?: return
        if (exiting) return
        exiting = true
        handler.postDelayed({
            if (view === v && exiting) {
                runCatching { windows.removeView(v) }
                view = null
                capture?.cancel()
                overHome = false
                backdrop.clear()
                exiting = false
                // Home's pill comes back as the capsule lands on it, not while it's still shrinking.
                open = false
                lifecycleRegistry.currentState = Lifecycle.State.CREATED
            }
        }, CcMorph.CLOSE_MS + 20L)
    }

    private fun apply(baked: dev.glasslauncher.glass.Backdrop) {
        if (view == null || exiting) return
        app.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { backdrop.swap(baked, animate = false) }
    }

    /** The app's last preview (already on the CPU, no readback), when a fresh capture isn't possible. */
    private fun usePreview() {
        val pkg = (service as? dev.glasslauncher.system.RemoteKeysService)?.frontApp ?: return
        capture?.cancel()
        capture = app.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val baked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                dev.glasslauncher.system.AppPreviews.load(service, pkg)?.let { runCatching { app.wallpapers.glassOnly(it) }.getOrNull() }
            } ?: return@launch
            if (backdrop.backdrop == null) apply(baked)
        }
    }

    /**
     * Over another app: capture the screen, and copy it off the GPU before the window appears. That copy
     * runs on the render thread, so made during the opening it cost frames (perf: 20% janky vs 6%). The
     * open waits for it at most [CAPTURE_WAIT_MS]; past that, or if the capture fails (rate-limited to one
     * a second, shared with the app switcher's previews), the app's last preview stands in.
     */
    private fun captureThenAttach(attach: () -> Unit) {
        var attached = false
        val go = { if (!attached) { attached = true; attach() } }
        if (android.os.Build.VERSION.SDK_INT < 30) { go(); return }
        handler.postDelayed({ if (!attached) { go(); usePreview() } }, CAPTURE_WAIT_MS)
        val ok = runCatching {
            service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val shot = android.graphics.Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    buffer.close()
                    if (shot == null || attached) { shot?.recycle(); return }
                    val soft = shot.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    shot.recycle()
                    go()
                    capture?.cancel()
                    capture = app.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        val baked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching { app.wallpapers.glassOnly(soft) }.getOrNull().also { soft.recycle() }
                        } ?: return@launch
                        apply(baked)
                    }
                }
                override fun onFailure(errorCode: Int) { go(); usePreview() }
            })
        }.isSuccess
        if (!ok) { go(); usePreview() }
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
                            if (k.action == KeyEvent.ACTION_UP) hide()
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
