package dev.glasslauncher.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.em
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import dev.glasslauncher.app
import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.apps.LoadedTile
import dev.glasslauncher.data.BackgroundMode
import dev.glasslauncher.data.FeaturedSourceId
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.ThemeMode
import dev.glasslauncher.data.appKey
import dev.glasslauncher.dream.AerialActivity
import dev.glasslauncher.featured.ExpandedShelf
import dev.glasslauncher.featured.ShelfTitle
import dev.glasslauncher.glass.BackdropState
import dev.glasslauncher.glass.GlassStyle
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.MotionBackground
import dev.glasslauncher.glass.glass
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.KeyDirection
import dev.glasslauncher.ui.LocalMetrics
import dev.glasslauncher.ui.Motion
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.LocalUiPrefs
import dev.glasslauncher.ui.Metrics
import dev.glasslauncher.ui.Palette
import dev.glasslauncher.ui.Safe
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type
import dev.glasslauncher.ui.UiPrefs
import dev.glasslauncher.widgets.IdleState
import dev.glasslauncher.widgets.StatusPill
import dev.glasslauncher.widgets.rememberIdleState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class HomeRequest { Home, ControlCenter, AppSwitcher, TvSettings, Settings }

private const val SLIDE_MS = 9_000L
private const val SLIDE_QUIET_MS = 3_000L
/** Focus rests this long on a top-row app before the shelf switches to its content. */
private const val SHELF_FOLLOW_MS = 450L
/** Focus resting this long on a tray app shows its hero (browsing past apps doesn't bake). */
private const val APP_HERO_MS = 250L
private const val GPU_TRIM_AFTER_MS = 4_000L
private const val APP_HERO_FADE_MS = 200
/** ...and this long shows its titles (tvOS 27: the shelf moves with the user first). */
private const val TITLES_AFTER_MS = 1_500L
/** Now Playing stays on Home this long after playback stops (track changes, short pauses). */
private const val NOW_PLAYING_LINGER_MS = 3_000L
private const val NOW_PLAYING_PAUSED_MS = 10 * 60_000L
/** Glass texture fade-in after a scroll back to the top lands. */
private const val TEXTURE_IN_MS = 220

const val SETTINGS_TILE_KEY = "glass:settings"

@OptIn(ExperimentalFoundationApi::class)
private object NoAutoScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/**
 * Home, laid out like tvOS 27: the featured artwork (or wallpaper, or Aerial video) fills the whole
 * screen, the top app row sits in a glass tray over it, the grid scrolls up over a blurred copy, and
 * pressing Up from the tray opens the featured shelf full screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(model: HomeModel, homePresses: Flow<HomeRequest>) {
    val context = LocalContext.current
    val graph = context.app
    val view = LocalView.current
    val cfg by model.config.collectAsStateWithLifecycle()
    val layout by model.layout.collectAsStateWithLifecycle()
    val backdrop = remember { BackdropState() }
    val dark = when (cfg.theme) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val prefs = remember(cfg) { UiPrefs.resolve(context, cfg) }
    val metrics = remember(cfg.textScale) { Metrics(cfg.textScale) }
    val palette = remember(backdrop.backdrop, dark, prefs) { Palette(light = !dark, highContrast = prefs.highContrast) }
    backdrop.reduceTransparency = prefs.reduceTransparency
    backdrop.light = !dark
    Type.bold = cfg.boldText

    var lastDockFocused by remember { mutableStateOf<String?>(null) }
    // Featured content drives the live backdrop. In "Focused app" mode the shelf follows the focused
    // top-row app once focus rests on it (browsing along the row doesn't fetch and bake every app).
    var shelfApp by remember { mutableStateOf<String?>(null) }
    // tvOS 27's Top Shelf moves with the user: the focused tray app's own hero within a beat (cached,
    // cheap), its titles only once focus has rested there. Browsing along the row never bakes titles.
    var heroApp by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastDockFocused) {
        val pkg = lastDockFocused?.removePrefix("app:") ?: return@LaunchedEffect
        delay(APP_HERO_MS)
        heroApp = pkg
    }
    var dwelled by remember { mutableStateOf(false) }
    LaunchedEffect(heroApp) {
        dwelled = false
        if (heroApp == null) return@LaunchedEffect
        delay(TITLES_AFTER_MS)
        dwelled = true
    }
    val appsWithRows by produceState(emptySet<String>(), layout.loaded) { value = dev.glasslauncher.featured.TvRows.packagesWithRows(context) }
    // Show Titles: Never means no title fetch at all (the shelf is only ever the app's hero).
    val featuredCfg = remember(cfg.featured, shelfApp, appsWithRows, cfg.topShelfTitles) {
        if (!cfg.topShelfTitles) null else dev.glasslauncher.featured.AppSources.effective(cfg.featured, shelfApp, appsWithRows)
    }
    LaunchedEffect(featuredCfg) { featuredCfg?.let { graph.featured.refresh(it) } }
    val featuredState by graph.featured.state.collectAsStateWithLifecycle()
    val feed = featuredState.feed?.takeIf { featuredCfg != null && it.items.isNotEmpty() }
    var heroIndex by remember { mutableIntStateOf(0) }
    // A different shelf starts from its first item.
    LaunchedEffect(feed?.items?.firstOrNull()?.id) { heroIndex = 0 }
    val hero = feed?.items?.getOrNull(heroIndex.coerceIn(0, (feed.items.size - 1).coerceAtLeast(0)))
    val wallpaper = if (dark) cfg.wallpaperDark else cfg.wallpaperLight

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val overlays = remember { mutableStateListOf<Overlay>() }
    var moving by remember { mutableStateOf<String?>(null) }
    var lastFocused by remember { mutableStateOf<String?>(null) }
    var focusedRow by remember { mutableIntStateOf(1) }
    val expand = remember { Animatable(0f) }
    var expanded by remember { mutableStateOf(false) }
    var shelfSheet by remember { mutableStateOf(false) }
    var pillFocused by remember { mutableStateOf(false) }
    val menuAnchor = remember { MenuAnchor() }
    val dissolve = remember { dev.glasslauncher.ui.ScreenDissolve(context, scope) }
    var homeFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val layer = rememberGraphicsLayer()
    val transition = remember { AppTransition(context, scope, backdrop, model) }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val cardRequester = remember { FocusRequester() }
    // Now Playing: Up from the tray reaches its controls, Up again the status pill (Control Center).
    val heroPlay = remember { FocusRequester() }
    var heroFocused by remember { mutableStateOf(false) }
    val pillRequester = remember { FocusRequester() }
    val idle = rememberIdleState(cfg.idleFadeMinutes)

    // Now Playing takes over the top shelf while music plays: the artwork becomes the backdrop (baked
    // like any slide) and the track replaces the shelf title. A pause or gap shorter than 3 s doesn't
    // flip Home back and forth.
    val nowPlaying by graph.nowPlaying.state.collectAsStateWithLifecycle()
    var takeover by remember { mutableStateOf<dev.glasslauncher.widgets.NowPlaying?>(null) }
    LaunchedEffect(heroApp, cfg.featured.mode, cfg.topShelfTitles, takeover != null) {
        if (cfg.featured.mode != dev.glasslauncher.data.FeaturedMode.FocusedApp || !cfg.topShelfTitles) return@LaunchedEffect
        // While Now Playing has the shelf, moving along the tray doesn't fetch each app's titles (perf: 19%
        // janky frames browsing with music up); the shelf catches up when the music stops.
        if (takeover != null) return@LaunchedEffect
        shelfApp = heroApp
    }
    LaunchedEffect(nowPlaying, cfg.showNowPlaying) {
        val np = nowPlaying
        val current = takeover
        when {
            cfg.showNowPlaying && np != null && np.playing -> takeover = np
            // Paused from the hero (or anywhere): the same track keeps Home for a while, controls and all.
            cfg.showNowPlaying && np != null && current != null && np.key == current.key -> {
                takeover = np
                delay(NOW_PLAYING_PAUSED_MS)
                takeover = null
            }
            current != null -> { delay(NOW_PLAYING_LINGER_MS); takeover = null }
        }
    }
    // The app's own hero shows until its titles are due: after a dwell, with titles on, and some to show.
    val titlesDue = cfg.topShelfTitles && feed != null && (dwelled || expanded)
    val appHeroShown = cfg.background == BackgroundMode.Featured && takeover == null && !expanded && heroApp != null && !titlesDue
    val appHeroPkg = heroApp.takeIf { appHeroShown }
    val appHeroes = HeroCache.app
    // Apps whose hero is full-screen art (no logo plate drawn over it).
    val artApps = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val sceneUrl = if (cfg.background == BackgroundMode.Featured && !appHeroShown) hero?.image else null
    // The slideshow bakes the next slide before switching, so its art and title dissolve in together.
    val prebaked = remember { arrayOfNulls<Pair<String, dev.glasslauncher.glass.Backdrop>>(1) }
    val takeoverArt = takeover?.art
    LaunchedEffect(sceneUrl, wallpaper, cfg.background, dark, takeoverArt, appHeroPkg) {
        if (takeoverArt != null) {
            backdrop.glassFades = true
            backdrop.swap(graph.wallpapers.fromImage(dev.glasslauncher.widgets.backdropArt(takeoverArt), light = !dark), animate = backdrop.backdrop != null)
            return@LaunchedEffect
        }
        if (appHeroPkg != null) {
            val key = "$appHeroPkg|$dark"
            val baked = appHeroes[key] ?: run {
                // The app's logo art full screen: Amazon's Fire TV icon, else its own banner (or icon) drawn sharp.
                val art = dev.glasslauncher.apps.AppArt.url(context, appHeroPkg)
                    ?.let { runCatching { graph.wallpapers.heroFromUrl(it, light = !dark) }.getOrNull() }
                    ?: appHeroBackdrop(graph, layout, appHeroPkg, dark)
                artApps[appHeroPkg] = art != null
                art
            }?.also { appHeroes[key] = it }
            // A short fade (APP_HERO_FADE_MS): the tray and pill dissolve with the backdrop on the same clock
            // (state.fade), else their glass changes colour before the background does. Only those two draw
            // twice, only for these 200 ms.
            if (baked != null) {
            backdrop.glassFades = true
            backdrop.swap(baked, animate = backdrop.backdrop != null, fadeMs = APP_HERO_FADE_MS); return@LaunchedEffect }
        }
        if (cfg.background == BackgroundMode.Motion) return@LaunchedEffect
        // The featured feed is usually a few ms behind the first composition: wait for it rather than
        // baking the wallpaper only to throw it away (the two bakes used to run in parallel at startup).
        if (sceneUrl == null && cfg.background == BackgroundMode.Featured && featuredCfg != null && backdrop.backdrop == null) delay(1_200)
        val ready = prebaked[0]?.takeIf { it.first == sceneUrl }?.second?.takeIf { it.isLight == !dark }
        prebaked[0] = null
        if (ready == null && expanded) delay(220) // let quick left/right browsing settle before re-baking the glass
        backdrop.glassFades = true
        val next = ready ?: sceneUrl?.let { runCatching { graph.wallpapers.fromUrl(it, light = !dark) }.getOrNull() } ?: graph.wallpapers.load(wallpaper, light = !dark)
        // A dissolve, never a cut; it's also what Reduce Motion asks for instead of movement.
        backdrop.swap(next, animate = backdrop.backdrop != null)
    }

    // Once Home has settled on a scene, the renderer's copies of the scenes it has left are released
    // (see GpuCaches): they otherwise pile up while browsing titles and app heroes.
    LaunchedEffect(backdrop.backdrop, expanded, overlays.size, ControlCenterWindow.open) {
        delay(GPU_TRIM_AFTER_MS)
        dev.glasslauncher.ui.GpuCaches.trim()
    }

    // Loading state: Home stays hidden until its first backdrop is baked and the app list is in, then
    // fades in whole, instead of assembling piece by piece (black screen, bare tray, tiles jumping).
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withTimeoutOrNull(8_000) {
            snapshotFlow { layout.loaded && (backdrop.backdrop != null || cfg.background == BackgroundMode.Motion) }.first { it }
            withFrameNanos { }
        }
        ready = true
        // Startup metric: the system logs "Fully drawn" (time to real content, not the first frame).
        runCatching { (view.context as? android.app.Activity ?: (context as? android.app.Activity))?.reportFullyDrawn() }
    }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(ready) { if (ready) reveal.animateTo(1f, tween(if (prefs.reduceMotion) 150 else 450)) }

    fun open(overlay: Overlay) {
        scope.launch {
            // The app switcher draws Home's baked blur instead; capturing the screen was most of its open cost.
            if (overlays.isEmpty() && overlay != Overlay.AppSwitcher) captureOverlay(view, backdrop.light, keepSharp = overlay == Overlay.ControlCenter || overlay is Overlay.FolderOpen).let {
                backdrop.overlay = it.frosted; backdrop.overlaySoft = it.soft; backdrop.overlaySharp = it.sharp
            }
            overlays.add(overlay)
        }
    }
    // Overlays on their way out (exit animation), drawn above the rest until it finishes.
    val exiting = remember { mutableStateListOf<Overlay>() }
    fun dismiss(overlay: Overlay) {
        exiting.add(overlay)
        scope.launch {
            // Control Center plays its bubble collapse; the others a short fade.
            delay(if (overlay == Overlay.ControlCenter) CcMorph.CLOSE_MS + 40L else Motion.OVERLAY_MS + 40L)
            exiting.remove(overlay)
            // Nothing open any more: let go of the screen captures (two full-screen images were held
            // after Control Center closed: perf-gate PSS 131 MB, over budget).
            if (overlays.isEmpty() && exiting.isEmpty()) {
                backdrop.overlay = null; backdrop.overlaySoft = null; backdrop.overlaySharp = null
            }
        }
    }
    fun closeTop() { overlays.removeLastOrNull()?.let(::dismiss) }
    /** Closes everything; only the top overlay is visible, so only it animates out. */
    fun closeAll() { overlays.lastOrNull()?.let(::dismiss); overlays.clear() }
    /** Control Center: the overlay window when Remote Buttons (accessibility) is on, else in Glass. */
    fun openControlCenter() {
        val window = dev.glasslauncher.system.RemoteKeysService.instance?.controlCenter
        if (window != null) window.toggle() else open(Overlay.ControlCenter)
    }
    fun firstKey(): String? = layout.dock.firstOrNull()?.let { appKey(it.packageName) } ?: layout.grid.firstOrNull()?.key

    fun exists(key: String) = key == SETTINGS_TILE_KEY ||
        layout.dock.any { appKey(it.packageName) == key } || layout.grid.any { it.key == key }
    /** Where focus goes when [key] has left Home: the folder the app went into, else the first app. */
    fun replacement(key: String): String? =
        layout.grid.firstOrNull { it is GridItem.FolderItem && it.apps.any { a -> appKey(a.packageName) == key } }?.key ?: firstKey()

    fun tryFocus(key: String) = runCatching { requester(key).requestFocus(FocusDirection.Enter) }.getOrDefault(false)

    suspend fun focusKey(key: String?) {
        val target = key?.let { if (exists(it)) it else replacement(it) } ?: return
        withFrameNanos { }
        if (tryFocus(target)) return
        // Grid rows off screen aren't composed yet: bring the row in, then focus it.
        val index = layout.grid.indexOfFirst { it.key == target }.takeIf { it >= 0 }
            ?: if (target == SETTINGS_TILE_KEY) layout.grid.size else null
        if (index != null) {
            listState.scrollToItem(2 + index / metrics.columns, -with(density) { metrics.gridPivot.roundToPx() })
            withFrameNanos { }
            if (tryFocus(target)) return
        }
        firstKey()?.let { if (it != target) tryFocus(it) }
    }

    suspend fun scrollToTop() {
        if (listState.firstVisibleItemIndex == 0) {
            listState.animateScrollBy(-listState.firstVisibleItemScrollOffset.toFloat(), Motion.scroll())
        } else listState.animateScrollToItem(0)
    }

    fun onRowFocused(index: Int) {
        focusedRow = index
        scope.launch {
            launch {
                val target = if (index >= 2) 1f else 0f
                // The blur eases in over the scroll rather than riding its front-loaded curve: on a ladder of
                // baked steps, the scroll's fast start reads as the backdrop lurching.
                if (target > 0f) backdrop.textureIn.snapTo(0f)
                if (prefs.reduceMotion) backdrop.wallpaperBlur.snapTo(target) else backdrop.wallpaperBlur.animateTo(target, tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                // Landed at the top: the glass frosts back over the settled frame rather than snapping.
                if (target == 0f) { if (prefs.reduceMotion) backdrop.textureIn.snapTo(1f) else backdrop.textureIn.animateTo(1f, tween(TEXTURE_IN_MS)) }
            }
            if (index <= 1) scrollToTop() else {
                // The focused row's tiles settle at the pivot; the first row has the tray gap above it.
                val pad = if (index == 2) metrics.trayToGrid else 0.dp
                val target = with(density) { (metrics.gridPivot - pad).toPx() }
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                // tvOS: full speed on the first frame, then exponential deceleration (not a spring from rest).
                if (info != null) listState.animateScrollBy(info.offset - target, Motion.scroll())
                else listState.animateScrollToItem(index, -target.toInt())
            }
        }
    }

    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        scope.launch {
            if (prefs.reduceMotion) expand.snapTo(if (value) 1f else 0f)
            else expand.animateTo(if (value) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = 420f))
        }
        scope.launch {
            withFrameNanos { }
            if (value) runCatching { cardRequester.requestFocus() } else focusKey(lastDockFocused ?: firstKey())
        }
    }

    LaunchedEffect(layout.loaded) {
        if (!layout.loaded) return@LaunchedEffect
        focusKey(firstKey())
        if (!cfg.tipsSeen) {
            delay(1500) // let the backdrop draw so the card's glass has something to blur
            open(Overlay.Tips)
        }
    }
    // The top shelf advances on its own, to the right, at rest and in full screen, as on tvOS. Any
    // browsing restarts the timer (heroIndex is a key). In full screen focus follows, so the row glides.
    // It pauses while the grid is up (the backdrop is blurred there) and waits for a few quiet seconds,
    // so a slide's bake and cross-fade never land on top of a scroll.
    // Paused while Home is hidden (an app is in front): no bakes behind the app, and Home comes back on
    // the slide it left on, which is what the app-close animation's blurred picture shows.
    val homeVisible = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    // Control Center over Home reuses Home's own glass (no screen capture needed).
    val homeScene = backdrop.backdrop
    LaunchedEffect(homeVisible, homeScene) { ControlCenterWindow.homeBackdrop = homeScene.takeIf { homeVisible } }
    // The effect above can't run once an app hides Home (no frames), so the lifecycle clears it on STOP.
    val latestScene = androidx.compose.runtime.rememberUpdatedState(homeScene)
    val homeLifecycle = LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(homeLifecycle) {
        val observer = ControlCenterWindow.homeLifecycleObserver { latestScene.value }
        homeLifecycle.addObserver(observer)
        onDispose { homeLifecycle.removeObserver(observer) }
    }
    LaunchedEffect(feed, heroIndex, expanded, overlays.isEmpty(), ControlCenterWindow.open, cfg.background, focusedRow >= 2, homeVisible, takeover != null, appHeroShown) {
        // Only while titles show: an app's own hero never advances or bakes slides.
        if (!homeVisible || takeover != null || appHeroShown) return@LaunchedEffect
        // Paused under Control Center too: its translucent tiles would change colour with every slide.
        if (feed == null || feed.items.size < 2 || overlays.isNotEmpty() || ControlCenterWindow.open || cfg.background != BackgroundMode.Featured || focusedRow >= 2) return@LaunchedEffect
        // Full screen is browsed by hand: the row, details and backdrop follow focus, not a timer (advancing
        // moved the backdrop and details off the focused card).
        if (expanded) return@LaunchedEffect
        delay(SLIDE_MS)
        while (idle.millisSinceInput() < SLIDE_QUIET_MS) delay(SLIDE_QUIET_MS - idle.millisSinceInput() + 50)
        val next = (heroIndex + 1) % feed.items.size
        val url = feed.items[next].image
        // Pre-baked at background priority, so a slide never competes with a scroll or focus move.
        if (url != null) runCatching { graph.wallpapers.fromUrl(url, background = true, light = !dark) }.getOrNull()?.let { prebaked[0] = url to it }
        feed.items[next].logo?.let { logo -> runCatching { coil3.SingletonImageLoader.get(context).execute(dev.glasslauncher.featured.logoRequest(context, logo)) } }
        heroIndex = next
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Coming back to Home (from an app, Aerials, a system screen): it settles in from slightly larger
    // instead of appearing in one frame, while the closing app animates away above it.
    val homeSettle = remember { Animatable(1f) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        var stopped = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { stopped = true; transition.onHomeStopped() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> if (stopped) {
                    stopped = false
                    // Back from an app Home opened: it shrinks into its tile instead (motion-spec §9).
                    if (!prefs.reduceMotion && transition.onHomeStarted { null }) return@LifecycleEventObserver
                    scope.launch {
                        if (prefs.reduceMotion) return@launch
                        homeSettle.snapTo(0f)
                        homeSettle.animateTo(1f, spring(dampingRatio = 1f, stiffness = 260f))
                    }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(cfg.aerialsIdleMinutes, cfg.screensaverMode, cfg.screensaverDuringMusic) {
        val limit = if (cfg.screensaverMode != dev.glasslauncher.data.ScreensaverMode.System) cfg.aerialsIdleMinutes * 60_000L else 0L
        if (limit <= 0) return@LaunchedEffect
        while (true) {
            val remaining = limit - idle.millisSinceInput()
            if (remaining > 0) delay(remaining)
            else {
                // Not over Now Playing (the album art is Home's screensaver while music plays), unless Show
                // During Music is on.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && overlays.isEmpty() && !ControlCenterWindow.open &&
                    (takeover == null || cfg.screensaverDuringMusic)) {
                    AerialActivity.start(context)
                }
                idle.touch()
            }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { overlays.isEmpty() }.collect { empty ->
            if (empty) {
                if (expanded) runCatching { cardRequester.requestFocus() } else focusKey(lastFocused ?: firstKey())
                // The leaving overlay's glass still samples the snapshot until its exit finishes.
                snapshotFlow { exiting.isEmpty() }.first { it }
                if (overlays.isEmpty()) backdrop.overlay = null
            }
        }
    }
    LaunchedEffect(layout, moving) { moving?.let { focusKey(it) } }
    // An app menu action can take the focused tile off Home (into a folder, hidden); config writes land
    // after the menu closes, so re-home focus whenever the layout changes under it.
    LaunchedEffect(layout) {
        val last = lastFocused ?: return@LaunchedEffect
        if (overlays.isEmpty() && !expanded && moving == null && !exists(last)) focusKey(last)
    }
    LaunchedEffect(backdrop.backdrop) {
        if (overlays.isNotEmpty()) {
            withFrameNanos { }
            captureOverlay(view, backdrop.light).let { backdrop.overlay = it.frosted; backdrop.overlaySoft = it.soft }
        }
    }
    // The hero leaving with focus inside it would leave nothing focused: back to the tray.
    LaunchedEffect(takeover == null) { if (takeover == null && heroFocused) { heroFocused = false; focusKey(firstKey()) } }
    LaunchedEffect(Unit) {
        homePresses.collect { request ->
            moving = null
            // A Home press (or a remote button) is activity too: Aerials wait for real idle time.
            idle.touch()
            when (request) {
                HomeRequest.Home -> {
                    dev.glasslauncher.system.RemoteKeysService.instance?.controlCenter?.hide()
                    closeAll()
                    setExpanded(false)
                    // In its own coroutine: a scroll interrupted by another one is cancelled, and that
                    // cancellation must not end this collector (Home would stop responding). Focus moves
                    // once the top row is on screen again: requested while the grid was still scrolled
                    // down, it found no tile and Home was left with no focus at all.
                    scope.launch { runCatching { scrollToTop() }; focusKey(firstKey()) }
                }
                // From remote buttons: pressing the same button again closes it.
                HomeRequest.ControlCenter -> { closeAll(); openControlCenter() }
                HomeRequest.AppSwitcher, HomeRequest.TvSettings, HomeRequest.Settings -> {
                    val target = when (request) {
                        HomeRequest.AppSwitcher -> Overlay.AppSwitcher
                        HomeRequest.Settings -> Overlay.Settings
                        else -> Overlay.TvSettings
                    }
                    val reopen = overlays.lastOrNull() != target
                    closeAll()
                    if (reopen) open(target)
                }
            }
        }
    }

    var backDownOnHome by remember { mutableStateOf(false) }
    /** Back on Home itself (no overlay): leave full screen, or go back to the top row. */
    fun homeBack() {
        when {
            expanded -> setExpanded(false)
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 -> scope.launch {
                runCatching { scrollToTop() }; focusKey(firstKey())
            }
        }
    }
    BackHandler {
        when {
            moving != null -> moving = null
            overlays.isNotEmpty() -> closeTop()
            else -> homeBack()
        }
    }

    val scaledDensity = Density(density.density, density.fontScale * cfg.textScale)
    CompositionLocalProvider(
        LocalMenuAnchor provides menuAnchor,
        dev.glasslauncher.ui.LocalScreenDissolve provides dissolve,
        LocalAppTransition provides transition.takeUnless { prefs.reduceMotion },
        LocalBackdrop provides backdrop,
        LocalPalette provides palette,
        LocalUiPrefs provides prefs,
        LocalMetrics provides metrics,
        LocalDensity provides scaledDensity,
        LocalTextStyle provides Type.body,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .onSizeChanged { backdrop.rootSize = it }
                // Black under the fade-in only; afterwards the backdrop is the only full-screen fill.
                .drawBehind { if (reveal.value < 1f) drawRect(Color.Black) }
                .onPreviewKeyEvent { ev ->
                    val e = ev.nativeKeyEvent
                    idle.touch()
                    if (e.action == AndroidKeyEvent.ACTION_DOWN) when (e.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { KeyDirection.dx = -1; KeyDirection.dy = 0 }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { KeyDirection.dx = 1; KeyDirection.dy = 0 }
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> { KeyDirection.dx = 0; KeyDirection.dy = -1 }
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { KeyDirection.dx = 0; KeyDirection.dy = 1 }
                    }
                    if (overlays.isNotEmpty()) { backDownOnHome = false; return@onPreviewKeyEvent false }
                    val key = moving
                    if (key != null) {
                        if (e.action == AndroidKeyEvent.ACTION_DOWN) handleMoveKey(e.keyCode, key, layout, model, metrics.columns) { moving = null }
                        return@onPreviewKeyEvent true
                    }
                    // Compose treats Back as "leave the focus group" first, so from the grid the first press
                    // only dropped focus and a second one went back up (found by e2e/test_home.py).
                    // Only a Back that went down on Home: the key-up of a Back that closed a menu isn't one.
                    if (e.keyCode == AndroidKeyEvent.KEYCODE_BACK && shelfSheet) return@onPreviewKeyEvent false
                    if (e.keyCode == AndroidKeyEvent.KEYCODE_BACK) {
                        if (e.action == AndroidKeyEvent.ACTION_DOWN) backDownOnHome = e.repeatCount == 0 || backDownOnHome
                        if (e.action == AndroidKeyEvent.ACTION_UP && backDownOnHome) { backDownOnHome = false; homeBack() }
                        return@onPreviewKeyEvent true
                    }
                    if (e.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when {
                        e.keyCode == AndroidKeyEvent.KEYCODE_SETTINGS -> { openControlCenter(); true }
                        // Up from the tray opens the featured shelf full screen ("Swipe up for full screen").
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP && !expanded && focusedRow == 1 && feed != null && cfg.topShelfTitles && takeover == null -> { setExpanded(true); true }
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP && !expanded && focusedRow == 1 && takeover != null && !pillFocused && !heroFocused ->
                            runCatching { heroPlay.requestFocus() }.isSuccess
                        // From the status pill, Down goes back to the Now Playing controls.
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && pillFocused && takeover != null && !expanded ->
                            runCatching { heroPlay.requestFocus() }.isSuccess
                        // Down from the pill (no music) or from the Now Playing controls: back to the tray app
                        // focus came up from (the pill sits outside the grid, so spatial search found nothing).
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && !expanded && (pillFocused || heroFocused) -> {
                            val key = lastDockFocused?.takeIf { exists(it) } ?: firstKey()
                            key != null && runCatching { requester(key).requestFocus() }.isSuccess
                        }
                        // Down always leaves full screen, wherever focus is (even mid-transition); from the
                        // status pill it goes back down to the titles instead.
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && expanded -> when {
                            pillFocused -> { runCatching { cardRequester.requestFocus() }; true }
                            // Focus already back on Home (a stale state): just let Down move as usual.
                            homeFocused -> { setExpanded(false); false }
                            else -> { setExpanded(false); true }
                        }
                        else -> false
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = idle.shift.x; translationY = idle.shift.y
                        // Hidden while an app open/close covers it completely: one less full screen to draw.
                        alpha = if ((transition.coverAlpha.value >= 1f && transition.cover != null) || backdrop.homeHidden) 0f else reveal.value
                        val settle = 1.04f - 0.04f * homeSettle.value
                        scaleX = settle; scaleY = settle
                    }
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) {
                if (cfg.background == BackgroundMode.Motion) {
                    MotionBackground(cfg.screensaver, backdrop, light = !dark, paused = overlays.isNotEmpty() || ControlCenterWindow.open || idle.idle)
                }
                BackdropLayer(backdrop, drawSharp = cfg.background != BackgroundMode.Motion)

                if (appHeroPkg != null && artApps[appHeroPkg] == true) {
                    Box(Modifier.testTag("top-shelf-app-hero:$appHeroPkg")) { Box(Modifier.size(1.dp).testTag("app-art:$appHeroPkg")) }
                }
                if (hero != null && takeover == null && !appHeroShown) {
                    ShelfTitle(
                        item = hero,
                        expanded = { expand.value },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("shelf-title")
                            .graphicsLayer {
                                val atRest = 1f - (backdrop.wallpaperBlur.value * 4f).coerceIn(0f, 1f)
                                // Gone within the first third of the expand, before the large title fades in.
                                alpha = idle.chromeAlpha * atRest * (1f - expand.value * 3f).coerceIn(0f, 1f)
                                translationY = -(if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 2000f)
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                    )
                }

                CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
                    HomeList(
                        layout = layout,
                        model = model,
                        listState = listState,
                        moving = moving,
                        // Only when there are titles to open (Up); an app with nothing shows no chevron.
                        showHint = feed != null && cfg.topShelfTitles && takeover == null,
                        hintAlpha = { (1f - backdrop.wallpaperBlur.value * 4f).coerceIn(0f, 1f) * (1f - expand.value) * idle.chromeAlpha },
                        requester = ::requester,
                        onFocused = { key, row ->
                            lastFocused = key
                            if (row == 1) lastDockFocused = key
                        },
                        onRowFocused = ::onRowFocused,
                        onAppMenu = { app, inDock -> open(Overlay.AppMenu(app, inDock, null, menuAnchor.bounds)) },
                        onFolderOpen = { open(Overlay.FolderOpen(it, menuAnchor.bounds)) },
                        onFolderMenu = { open(Overlay.FolderMenu(it)) },
                        onSettings = { open(Overlay.Settings) },
                        launch = { app, bounds -> model.launch(app, view, bounds) },
                        modifier = Modifier
                            .graphicsLayer {
                                // Expanding the shelf slides the tray and grid off the bottom of the screen.
                                translationY = expand.value * size.height * 0.62f
                                alpha = 1f - expand.value * 0.999f
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
                            .focusProperties { canFocus = !expanded }
                            // Focus on the tray or grid means full screen is over, however it got there.
                            .onFocusChanged { homeFocused = it.hasFocus; if (it.hasFocus && expanded) setExpanded(false) },
                    )
                }
                // While music plays, the Now Playing hero takes the featured title's place (dissolving both
                // ways, in step with the backdrop). Drawn after the list: the list is full screen (empty up
                // here), and Compose leaves anything a later sibling covers out of the accessibility tree.
                androidx.compose.animation.AnimatedVisibility(
                    visible = takeover != null,
                    enter = androidx.compose.animation.fadeIn(tween(550)),
                    exit = androidx.compose.animation.fadeOut(tween(450)),
                    modifier = Modifier.graphicsLayer {
                        alpha = idle.chromeAlpha * (1f - (backdrop.wallpaperBlur.value * 4f).coerceIn(0f, 1f))
                        translationY = -(if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 2000f)
                    },
                ) {
                    val shown = takeover ?: nowPlaying
                    if (shown != null) {
                        // Live position and play state while it's up; the takeover itself lingers through gaps.
                        val live = nowPlaying?.takeIf { it.key == shown.key } ?: shown
                        val onLight = backdrop.backdrop?.artLight(0.12f, 0.15f, 0.88f, 0.65f) == true
                        Box(Modifier.fillMaxWidth().padding(top = metrics.chromeInset + 70.dp).onFocusChanged { heroFocused = it.hasFocus }, contentAlignment = Alignment.TopCenter) {
                            dev.glasslauncher.widgets.NowPlayingHero(live, onLight, heroPlay, pillRequester)
                        }
                    }
                }

                if (feed != null && (expanded || expand.value > 0f)) {
                    ExpandedShelf(
                        feed = feed,
                        index = heroIndex,
                        progress = { expand.value },
                        firstCard = cardRequester,
                        onIndex = { heroIndex = it },
                        onExitDown = { setExpanded(false) },
                        modifier = Modifier.fillMaxSize(),
                        onSheet = { shelfSheet = it },
                    )
                }
            }

            StatusPill(
                cfg = cfg,
                idle = idle,
                focusable = expanded || feed == null || takeover != null,
                onSelect = { openControlCenter() },
                modifier = Modifier.align(Alignment.TopEnd).focusRequester(pillRequester).onFocusChanged { pillFocused = it.hasFocus },
                // Control Center draws its own clock in this corner.
                fade = { if (overlays.lastOrNull() == Overlay.ControlCenter || ControlCenterWindow.open) 0f else (1f - backdrop.wallpaperBlur.value) * reveal.value },
            )

            moving?.let { MoveBanner(it, layout, Modifier.align(Alignment.BottomCenter)) }

            // One loop over open and leaving overlays, keyed by identity, so an overlay keeps its state
            // (and its animation) when it moves from open to leaving.
            (overlays + exiting.filter { it !in overlays }).forEach { overlay ->
                androidx.compose.runtime.key(System.identityHashCode(overlay)) {
                    val leaving = overlay in exiting && overlay !in overlays
                    val top = !leaving && overlay === overlays.lastOrNull()
                    // Tagged for the device tests (e2e/): which overlays are up, and which one is on top.
                    val tag = (if (leaving) "overlay-leaving:" else if (top) "overlay-top:" else "overlay:") + (overlay::class.simpleName ?: "Overlay")
                    Box(Modifier.fillMaxSize().testTag(tag).graphicsLayer { alpha = if (top || leaving || overlay is Overlay.FolderOpen) 1f else 0f }) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalOverlayExiting provides leaving) {
                            OverlayContent(
                                overlay = overlay,
                                model = model,
                                layout = layout,
                                cfg = cfg,
                                active = top,
                                open = ::open,
                                close = ::closeTop,
                                closeAll = ::closeAll,
                                startMove = { key -> closeAll(); moving = key },
                            )
                        }
                    }
                }
            }

            if (reveal.value < 1f) StartupMark(loading = !ready, alpha = { 1f - reveal.value })

            AppTransitionLayer(transition, warmSource = backdrop.backdrop?.blurredSoftware.takeIf { ready })

            // The frame before an appearance/background/text-size change, fading out over the new one.
            dissolve.image?.let { frame ->
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawImage(frame, dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = dissolve.alpha.value)
                }
            }
        }
    }
}

/**
 * Shown only if startup takes more than a moment: the name, breathing slowly in the middle of a black
 * screen, then fading out as Home fades in.
 */
@Composable
private fun BoxScope.StartupMark(loading: Boolean, alpha: () -> Float) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(400); shown = true }
    if (!shown) return
    val breath = remember { Animatable(0.35f) }
    LaunchedEffect(loading) {
        if (loading) breath.animateTo(0.7f, androidx.compose.animation.core.infiniteRepeatable(tween(1400), androidx.compose.animation.core.RepeatMode.Reverse))
    }
    Text(
        "Glass",
        style = Type.title.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        color = Color.White,
        modifier = Modifier.align(Alignment.Center).graphicsLayer { this.alpha = breath.value * alpha() },
    )
}

private fun handleMoveKey(keyCode: Int, key: String, layout: HomeLayout, model: HomeModel, columns: Int, done: () -> Unit) {
    val pkg = key.removePrefix("app:")
    val inDock = key.startsWith("app:") && layout.dock.any { it.packageName == pkg }
    val gridIndex = layout.grid.indexOfFirst { it.key == key }
    when (keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> model.move(key, -1)
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> model.move(key, 1)
        AndroidKeyEvent.KEYCODE_DPAD_UP -> when {
            inDock -> Unit
            gridIndex in 0 until columns && key.startsWith("app:") -> model.moveIntoDock(pkg)
            else -> model.move(key, -columns)
        }
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> when {
            inDock -> model.moveOutOfDock(pkg)
            !model.move(key, columns) -> model.move(key, layout.grid.lastIndex - gridIndex)
        }
        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_BACK -> done()
    }
}

/**
 * One opaque full-screen draw per frame: the backdrop's blur ladder step for the current scroll
 * blur. Only during a scene change is the outgoing backdrop drawn underneath and faded.
 */
@Composable
private fun BackdropLayer(state: BackdropState, drawSharp: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val dst = IntSize(size.width.toInt(), size.height.toInt())
        val blur = state.wallpaperBlur.value
        fun step(b: dev.glasslauncher.glass.Backdrop) = b.ladder[(blur * b.ladder.lastIndex).roundToInt().coerceIn(0, b.ladder.lastIndex)]
        // In Motion mode the video shows through until the grid is scrolled into view.
        if (!drawSharp && blur < 0.05f) return@Canvas
        val alphaForMotion = if (drawSharp) 1f else ((blur - 0.05f) * 3f).coerceIn(0f, 1f)
        val previous = state.previous
        val current = state.backdrop ?: return@Canvas
        // Mid-scroll, blend the two neighbouring blur steps so the blur (and its appearance wash) ramps
        // continuously instead of stepping through the ladder. Two full-screen draws only while it moves.
        val pos = blur * current.ladder.lastIndex
        val lower = pos.toInt().coerceIn(0, current.ladder.lastIndex)
        val frac = pos - lower
        if (previous == null && drawSharp && frac > 0.04f && frac < 0.96f && lower < current.ladder.lastIndex) {
            drawImage(current.ladder[lower], dstSize = dst, filterQuality = FilterQuality.Low)
            drawImage(current.ladder[lower + 1], dstSize = dst, alpha = frac, filterQuality = FilterQuality.Low)
            return@Canvas
        }
        if (previous != null) drawImage(step(previous), dstSize = dst, alpha = alphaForMotion, filterQuality = FilterQuality.Low)
        drawImage(
            step(current),
            dstSize = dst,
            alpha = alphaForMotion * if (previous != null) state.fade.value else 1f,
            filterQuality = FilterQuality.Low,
        )
    }
}

/**
 * Left/Right at the end of a row does nothing, as on tvOS, instead of letting focus search wander to
 * whatever is geometrically nearest (the status pill, or the end of the previous row).
 */
fun Modifier.stopAtRowEnds(): Modifier = focusProperties {
    onExit = {
        if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Left ||
            requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Right
        ) cancelFocusChange()
    }
}.then(Modifier.focusGroup())

private sealed interface Cell {
    data class Item(val item: GridItem) : Cell
    data object Settings : Cell
}

@Composable
private fun HomeList(
    layout: HomeLayout,
    model: HomeModel,
    listState: LazyListState,
    moving: String?,
    showHint: Boolean,
    hintAlpha: () -> Float,
    requester: (String) -> FocusRequester,
    onFocused: (String, Int) -> Unit,
    onRowFocused: (Int) -> Unit,
    onAppMenu: (AppEntry, Boolean) -> Unit,
    onFolderOpen: (String) -> Unit,
    onFolderMenu: (dev.glasslauncher.data.Folder) -> Unit,
    onSettings: () -> Unit,
    launch: (AppEntry, Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = LocalMetrics.current
    val screen = androidx.compose.ui.platform.LocalConfiguration.current
    val trayTop = m.trayTop(screen.screenWidthDp.dp, screen.screenHeightDp.dp)
    // With larger text the tray holds fewer columns: its other apps lead the grid instead of vanishing.
    val dockShown = layout.dock.take(m.columns)
    val dockExtra = layout.dock.drop(m.columns)
    val cells = remember(layout.grid, dockExtra) {
        dockExtra.map<dev.glasslauncher.apps.AppEntry, Cell> { Cell.Item(GridItem.App(it)) } + layout.grid.map<GridItem, Cell> { Cell.Item(it) } + Cell.Settings
    }
    val rows = remember(cells, m.columns) { cells.chunked(m.columns) }
    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        contentPadding = PaddingValues(bottom = 160.dp),
        modifier = modifier.fillMaxSize().testTag("home"),
    ) {
        item(key = "shelf") {
            Box(Modifier.fillMaxWidth().height(trayTop), contentAlignment = Alignment.BottomCenter) {
                if (showHint) ShelfHint(hintAlpha)
            }
        }
        item(key = "dock") {
            DockTray(
                apps = dockShown,
                model = model,
                moving = moving,
                requester = requester,
                onFocused = { onFocused(it, 1) },
                onAppMenu = { onAppMenu(it, true) },
                launch = launch,
                modifier = Modifier.onFocusChanged { if (it.hasFocus) onRowFocused(1) },
            )
        }
        itemsIndexed(rows, key = { i, _ -> "row-$i" }) { i, row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(m.gutter),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = m.inset, end = m.inset, top = if (i == 0) m.trayToGrid else 0.dp)
                    .onFocusChanged { if (it.hasFocus) onRowFocused(i + 2) }
                    .stopAtRowEnds(),
            ) {
                row.forEach { cell ->
                    Box(Modifier.weight(1f)) {
                        when (cell) {
                            is Cell.Settings -> SettingsCell(requester(SETTINGS_TILE_KEY), { onFocused(SETTINGS_TILE_KEY, i + 2) }, onSettings)
                            is Cell.Item -> when (val item = cell.item) {
                                is GridItem.App -> AppCell(
                                    app = item.app,
                                    model = model,
                                    moving = moving == item.key,
                                    focusRequester = requester(item.key),
                                    onFocused = { onFocused(item.key, i + 2) },
                                    onMenu = { onAppMenu(item.app, false) },
                                    launch = launch,
                                )
                                is GridItem.FolderItem -> FolderCell(
                                    item = item,
                                    model = model,
                                    moving = moving == item.key,
                                    focusRequester = requester(item.key),
                                    onFocused = { onFocused(item.key, i + 2) },
                                    onOpen = { onFolderOpen(item.folder.id) },
                                    onMenu = { onFolderMenu(item.folder) },
                                )
                            }
                        }
                    }
                }
                repeat(m.columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** tvOS shows "⌃ Swipe up for full screen" just above the tray. */
@Composable
private fun ShelfHint(alpha: () -> Float) {
    // tvOS 27: just a wide, flat chevron above the tray (no words), on the art itself.
    val onLight = LocalBackdrop.current.backdrop?.artLight(0.35f, 0.6f, 0.65f, 0.7f) == true
    val color = (if (onLight) Color(0xFF0E1015) else Color.White).copy(alpha = 0.4f)
    androidx.compose.foundation.Canvas(
        Modifier.padding(bottom = 14.dp).size(28.dp, 9.dp).graphicsLayer { this.alpha = alpha() }.testTag("shelf-chevron"),
    ) {
        // The provided SVG's shape (55×17 viewbox): a stretched chevron with round caps.
        val sx = size.width / 55f; val sy = size.height / 17f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(3f * sx, 14f * sy); lineTo(27.5f * sx, 3f * sy); lineTo(52f * sx, 14f * sy)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.6f * sy * 17f / 9f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** HOME-01: the top row lives in one large rounded glass slab spanning nearly the full width. */
@Composable
private fun DockTray(
    apps: List<AppEntry>,
    model: HomeModel,
    moving: String?,
    requester: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
    onAppMenu: (AppEntry) -> Unit,
    launch: (AppEntry, Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val m = LocalMetrics.current
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = m.trayMargin)
            .glass(LocalBackdrop.current, RoundedCornerShape(m.trayRadius), GlassStyle.shelf(palette.light))
            .testTag("tray")
            // Icons only (no names under them), so they sit centred: equal space above and below.
            .padding(horizontal = m.inset - m.trayMargin, vertical = m.trayPadVertical),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(m.gutter), modifier = Modifier.fillMaxWidth().stopAtRowEnds().testTag("dock")) {
            apps.take(m.columns).forEach { app ->
                Box(Modifier.weight(1f)) {
                    val key = appKey(app.packageName)
                    AppCell(
                        app = app,
                        model = model,
                        moving = moving == key,
                        focusRequester = requester(key),
                        onFocused = { onFocused(key) },
                        onMenu = { onAppMenu(app) },
                        launch = launch,
                        // The top row is icons only (no name under the focused app).
                        showLabel = false,
                        floatingLabel = false,
                    )
                }
            }
            repeat(m.columns - apps.take(m.columns).size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
fun AppCell(
    app: AppEntry,
    model: HomeModel,
    moving: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onMenu: () -> Unit,
    launch: ((AppEntry, Rect?) -> Unit)? = null,
    showLabel: Boolean = true,
    floatingLabel: Boolean = false,
) {
    val tile = rememberTile(model, app)
    val art = tile?.image
    val isNew by rememberTileValue(model, app.packageName) { model.isNew(app.packageName, it) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val anchorStore = LocalMenuAnchor.current
    val launchView = LocalView.current
    val transition = LocalAppTransition.current
    TileWithLabel(
        label = app.label,
        tag = "app:${app.packageName}",
        moving = moving,
        isNew = isNew,
        glowColor = tile?.let { Color(it.glow) },
        focusRequester = focusRequester,
        onFocused = onFocused,
        // Launch from the tile as drawn (focused, 1.2x), so the app zooms out of what you see.
        onClick = {
            val from = bounds?.let { val dx = it.width * 0.1f; val dy = it.height * 0.1f; Rect(it.left - dx, it.top - dy, it.right + dx, it.bottom + dy) }
            when {
                transition != null && from != null -> transition.open(app, from, art, launchView)
                launch != null -> launch(app, from)
                else -> model.launch(app, launchView, from)
            }
        },
        onMenu = { anchorStore.bounds = bounds; onMenu() },
        showLabel = showLabel,
        floatingLabel = floatingLabel,
        tileModifier = Modifier.onGloballyPositioned { bounds = it.boundsInWindow() },
    ) {
        art?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun FolderCell(
    item: GridItem.FolderItem,
    model: HomeModel,
    moving: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val anchorStore = LocalMenuAnchor.current
    TileWithLabel(
        label = item.folder.name,
        tag = "folder:${item.folder.id}",
        moving = moving,
        isNew = false,
        focusRequester = focusRequester,
        onFocused = onFocused,
        onClick = { anchorStore.bounds = bounds; onOpen() },
        tileModifier = Modifier.onGloballyPositioned { bounds = it.boundsInWindow() },
        onMenu = onMenu,
        glassBackground = true,
    ) {
        // HOME-06: a glass tile holding a 3x2 mini grid, filled from the top left like tvOS.
        Column(
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 10.dp),
        ) {
            item.apps.take(6).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { app ->
                        val art = rememberArt(model, app)
                        Box(Modifier.weight(1f).aspectRatio(5f / 3f).graphicsLayer { shape = RoundedCornerShape(5.dp); clip = true }) {
                            art?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The launcher's own Settings, presented like the Apple TV Settings app tile at the end of the grid. */
@Composable
private fun SettingsCell(focusRequester: FocusRequester, onFocused: () -> Unit, onOpen: () -> Unit) {
    TileWithLabel(
        label = "Settings",
        tag = "settings-tile",
        moving = false,
        isNew = false,
        focusRequester = focusRequester,
        onFocused = onFocused,
        onClick = onOpen,
        onMenu = onOpen,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF9CA3AF), Color(0xFF5B616B)))),
            contentAlignment = Alignment.Center,
        ) {
            dev.glasslauncher.widgets.GearIcon(Color(0xFFF2F3F5), size = 44.dp)
        }
    }
}

/**
 * A 5:3 tile with the tvOS focus treatment, and its name in small grey text below it only while
 * focused (HOME-04). Newly installed apps get a blue dot before the name.
 */
@Composable
fun TileWithLabel(
    label: String,
    tag: String,
    moving: Boolean,
    isNew: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    /** The colour the tile glows in under it, once its art has loaded (see [FocusTile]). */
    glowColor: Color? = null,
    glassBackground: Boolean = false,
    showLabel: Boolean = true,
    /** In the tray: the name still shows on focus, in the tray's own bottom padding (no row gap to reserve). */
    floatingLabel: Boolean = false,
    tileModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val m = LocalMetrics.current
    var focused by remember { mutableStateOf(false) }
    // Tile first, then name: the new label waits ~80 ms and fades in fast; the old one fades over ~130 ms.
    val labelAlpha by animateFloatAsState(if (focused) 1f else 0f, if (focused) Motion.labelIn() else Motion.labelOut(), label = "label")
    Column {
    Box {
        FocusTile(
            // The blue dot isn't colour-only: "New" is spoken too.
            label = if (isNew) "$label, New" else label,
            onClick = onClick,
            onLongClick = onMenu,
            wiggle = moving,
            shadow = !glassBackground,
            glowColor = glowColor,
            edgeLight = true,
            shape = RoundedCornerShape(m.tileRadius),
            onFocusChange = { focused = it; if (it) onFocused() },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(m.tileAspect)
                .then(tileModifier)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .testTag(tag),
        ) {
            // Inside the tile's scaled layer, so the glass grows with its contents on focus. Frosted,
            // not the tray's clear glass: no refracted edge band per folder.
            if (glassBackground) {
                Box(Modifier.fillMaxSize().glass(LocalBackdrop.current, RoundedCornerShape(m.tileRadius), GlassStyle.shelf(palette.light).copy(clear = false))) { content() }
            } else content()
        }
        if (showLabel || floatingLabel || isNew) {
            // Sits in the row gap below the tile so labels never change the layout.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = (if (floatingLabel) 7.dp else 12.dp).toPx() + size.height
                        alpha = labelAlpha
                    },
            ) {
                if (isNew) Box(Modifier.size(6.dp).background(palette.accent, CircleShape))
                if (showLabel || floatingLabel) Text(
                    text = label,
                    style = Type.caption.copy(fontSize = Type.caption.fontSize * 0.98f),
                    color = palette.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (showLabel) Spacer(Modifier.height(m.labelSpace))
    }
}

/**
 * One value a tile derives from the config. A tile recomposes only when that value changes, not on every
 * config edit (there are dozens of tiles, and most edits touch nothing they draw).
 */
@Composable
private fun <T> rememberTileValue(model: HomeModel, vararg keys: Any?, derive: (dev.glasslauncher.data.LauncherConfig) -> T): State<T> {
    val cfg = model.config.collectAsStateWithLifecycle()
    return remember(model, *keys) { derivedStateOf { derive(cfg.value) } }
}

@Composable
fun rememberArt(model: HomeModel, app: AppEntry): ImageBitmap? = rememberTile(model, app)?.image

/** The app's tile art with its glow colour, null until it has loaded. */
@Composable
fun rememberTile(model: HomeModel, app: AppEntry): LoadedTile? {
    val context = LocalContext.current
    val graph = context.app
    val spec by rememberTileValue(model, app) { model.spec(app, it) }
    val tile by produceState(graph.tileArt.peekTile(spec), spec) { value = graph.tileArt.loadTile(spec) }
    return tile
}

/**
 * The Top Shelf's own hero for a tray app (tvOS's "logo only" shelf, e.g. Peacock's): the app's logo
 * large and centred on its own brand colour (the backdrop), or for full-bleed banners the banner itself
 * over a blurred wash of it.
 */
/** The app's own logo art (banner or icon, drawn sharp) as its hero, for apps Amazon has no Fire TV icon for. */
private suspend fun appHeroBackdrop(
    graph: dev.glasslauncher.GlassApp, layout: HomeLayout, pkg: String, dark: Boolean,
): dev.glasslauncher.glass.Backdrop? = kotlinx.coroutines.withContext(dev.glasslauncher.glass.WallpaperLoader.BakeDispatcher) {
    val app = layout.installed.firstOrNull { it.packageName == pkg } ?: return@withContext null
    val art = graph.tileArt.heroArt(app) ?: return@withContext null
    runCatching { graph.wallpapers.fromHeroArt(art, light = !dark) }.getOrNull().also { art.recycle() }
}

/** tvOS shows guidance while rearranging; without it the wiggle mode feels like a dead end. */
@Composable
private fun MoveBanner(key: String, layout: HomeLayout, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val inDock = layout.dock.any { appKey(it.packageName) == key }
    val hint = androidx.compose.ui.text.buildAnnotatedString {
        // The D-pad directions are drawn as icons (one symbol set), with words for accessibility.
        fun arrows(vararg d: String) = d.forEachIndexed { i, k -> if (i > 0) append(" "); appendInlineContent(k, k) }
        if (inDock) {
            arrows("Left", "Right"); append("  Rearrange   ·   "); arrows("Down"); append("  Move to Apps   ·   Select  Done")
        } else {
            arrows("Left", "Right", "Up", "Down"); append("  Move   ·   "); arrows("Up"); append(" on first row  Add to Top Row   ·   Select  Done")
        }
    }
    val arrowIcons = remember(palette.primary) {
        listOf("Right" to 0f, "Down" to 90f, "Left" to 180f, "Up" to 270f).associate { (k, deg) ->
            k to androidx.compose.foundation.text.InlineTextContent(
                androidx.compose.ui.text.Placeholder(1.em, 1.em, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter),
            ) {
                Image(
                    androidx.compose.ui.res.painterResource(dev.glasslauncher.R.drawable.ic_play_arrow), null,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(palette.primary),
                    modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = deg },
                )
            }
        }
    }
    Box(
        modifier
            .padding(bottom = Safe.bottom)
            .glass(LocalBackdrop.current, Shapes.pill, GlassStyle.panel(palette.light))
            .padding(horizontal = 28.dp, vertical = 14.dp)
            .testTag("move-banner"),
    ) {
        androidx.compose.foundation.text.BasicText(hint, style = Type.secondary.copy(color = palette.primary), inlineContent = arrowIcons)
    }
}
