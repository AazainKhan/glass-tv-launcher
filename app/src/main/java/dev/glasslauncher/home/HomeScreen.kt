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
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import dev.glasslauncher.app
import dev.glasslauncher.apps.AppEntry
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
fun HomeScreen(model: HomeModel, homePresses: Flow<Unit>) {
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
    val palette = remember(backdrop.backdrop, dark, prefs) { Palette(light = backdrop.backdrop?.isLight ?: !dark, highContrast = prefs.highContrast) }
    backdrop.reduceTransparency = prefs.reduceTransparency

    // Featured content drives the live backdrop.
    LaunchedEffect(cfg.featured) { graph.featured.refresh(cfg.featured) }
    val featuredState by graph.featured.state.collectAsStateWithLifecycle()
    val feed = featuredState.feed?.takeIf { cfg.featured.source != FeaturedSourceId.Off && it.items.isNotEmpty() }
    var heroIndex by remember { mutableIntStateOf(0) }
    val hero = feed?.items?.getOrNull(heroIndex.coerceIn(0, (feed.items.size - 1).coerceAtLeast(0)))
    val wallpaper = if (dark) cfg.wallpaperDark else cfg.wallpaperLight

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val overlays = remember { mutableStateListOf<Overlay>() }
    var moving by remember { mutableStateOf<String?>(null) }
    var lastFocused by remember { mutableStateOf<String?>(null) }
    var lastDockFocused by remember { mutableStateOf<String?>(null) }
    var focusedRow by remember { mutableIntStateOf(1) }
    val expand = remember { Animatable(0f) }
    var expanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val layer = rememberGraphicsLayer()
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val cardRequester = remember { FocusRequester() }
    val idle = rememberIdleState(cfg.idleFadeMinutes)

    val sceneUrl = if (cfg.background == BackgroundMode.Featured) hero?.image else null
    LaunchedEffect(sceneUrl, wallpaper, cfg.background) {
        if (cfg.background == BackgroundMode.Motion) return@LaunchedEffect
        if (expanded) delay(220) // let quick left/right browsing settle before re-baking the glass
        val next = sceneUrl?.let { runCatching { graph.wallpapers.fromUrl(it) }.getOrNull() } ?: graph.wallpapers.load(wallpaper)
        backdrop.swap(next, animate = backdrop.backdrop != null && !prefs.reduceMotion)
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
    }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(ready) { if (ready) reveal.animateTo(1f, tween(if (prefs.reduceMotion) 150 else 450)) }

    fun open(overlay: Overlay) {
        scope.launch {
            if (overlays.isEmpty()) backdrop.overlay = captureBlurred(layer)
            overlays.add(overlay)
        }
    }
    fun closeTop() { overlays.removeLastOrNull() }
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
            listState.animateScrollBy(-listState.firstVisibleItemScrollOffset.toFloat(), spring(stiffness = Spring.StiffnessMediumLow))
        } else listState.animateScrollToItem(0)
    }

    fun onRowFocused(index: Int) {
        focusedRow = index
        scope.launch {
            launch {
                val target = if (index >= 2) 1f else 0f
                if (prefs.reduceMotion) backdrop.wallpaperBlur.snapTo(target) else backdrop.wallpaperBlur.animateTo(target, tween(350))
            }
            if (index <= 1) scrollToTop() else {
                // The focused row's tiles settle at the pivot; the first row has the tray gap above it.
                val pad = if (index == 2) metrics.trayToGrid else 0.dp
                val target = with(density) { (metrics.gridPivot - pad).toPx() }
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (info != null) listState.animateScrollBy(info.offset - target, spring(stiffness = Spring.StiffnessMediumLow))
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
    // The top shelf advances on its own while Home is at rest, as on tvOS.
    LaunchedEffect(feed, expanded, overlays.isEmpty(), idle.idle, prefs.reduceMotion, cfg.background) {
        if (feed == null || expanded || overlays.isNotEmpty() || idle.idle || prefs.reduceMotion || cfg.background != BackgroundMode.Featured) return@LaunchedEffect
        while (true) {
            delay(12_000)
            heroIndex = (heroIndex + 1) % feed.items.size
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(cfg.aerialsOnIdleMinutes) {
        val limit = cfg.aerialsOnIdleMinutes * 60_000L
        if (limit <= 0) return@LaunchedEffect
        while (true) {
            val remaining = limit - idle.millisSinceInput()
            if (remaining > 0) delay(remaining)
            else {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && overlays.isEmpty()) {
                    context.startActivity(android.content.Intent(context, AerialActivity::class.java))
                }
                idle.touch()
            }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { overlays.isEmpty() }.collect { empty ->
            if (empty) {
                backdrop.overlay = null
                if (expanded) runCatching { cardRequester.requestFocus() } else focusKey(lastFocused ?: firstKey())
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
            backdrop.overlay = captureBlurred(layer)
        }
    }
    LaunchedEffect(Unit) {
        homePresses.collect {
            overlays.clear()
            moving = null
            setExpanded(false)
            scrollToTop()
            focusKey(firstKey())
        }
    }

    BackHandler {
        when {
            moving != null -> moving = null
            overlays.isNotEmpty() -> closeTop()
            expanded -> setExpanded(false)
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 -> scope.launch {
                scrollToTop(); focusKey(firstKey())
            }
        }
    }

    val scaledDensity = Density(density.density, density.fontScale * cfg.textScale)
    CompositionLocalProvider(
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
                    if (overlays.isNotEmpty()) return@onPreviewKeyEvent false
                    val key = moving
                    if (key != null) {
                        if (e.action == AndroidKeyEvent.ACTION_DOWN) handleMoveKey(e.keyCode, key, layout, model, metrics.columns) { moving = null }
                        return@onPreviewKeyEvent true
                    }
                    if (e.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when {
                        e.keyCode == AndroidKeyEvent.KEYCODE_SETTINGS -> { open(Overlay.ControlCenter); true }
                        // Up from the tray opens the featured shelf full screen ("Swipe up for full screen").
                        e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP && !expanded && focusedRow == 1 && feed != null -> { setExpanded(true); true }
                        else -> false
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = idle.shift.x; translationY = idle.shift.y; alpha = reveal.value }
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) {
                if (cfg.background == BackgroundMode.Motion) {
                    MotionBackground(cfg.screensaver, backdrop, paused = overlays.isNotEmpty() || idle.idle)
                }
                BackdropLayer(backdrop, drawSharp = cfg.background != BackgroundMode.Motion)

                // Title of the current featured item, top left, above the tray.
                if (hero != null) {
                    ShelfTitle(
                        item = hero,
                        expanded = { expand.value },
                        modifier = Modifier
                            .fillMaxSize()
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
                        showHint = feed != null,
                        hintAlpha = { (1f - backdrop.wallpaperBlur.value * 4f).coerceIn(0f, 1f) * (1f - expand.value) * idle.chromeAlpha },
                        requester = ::requester,
                        onFocused = { key, row ->
                            lastFocused = key
                            if (row == 1) lastDockFocused = key
                        },
                        onRowFocused = ::onRowFocused,
                        onAppMenu = { app, inDock -> open(Overlay.AppMenu(app, inDock, null)) },
                        onFolderOpen = { open(Overlay.FolderOpen(it)) },
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
                            .focusProperties { canFocus = !expanded },
                    )
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
                    )
                }
            }

            StatusPill(
                cfg = cfg,
                idle = idle,
                focusable = expanded || feed == null,
                onSelect = { open(Overlay.ControlCenter) },
                modifier = Modifier.align(Alignment.TopEnd),
                // Control Center draws its own clock in this corner.
                fade = { if (overlays.lastOrNull() == Overlay.ControlCenter) 0f else (1f - backdrop.wallpaperBlur.value) * reveal.value },
            )

            moving?.let { MoveBanner(it, layout, Modifier.align(Alignment.BottomCenter)) }

            overlays.forEachIndexed { index, overlay ->
                val top = index == overlays.lastIndex
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (top || overlay is Overlay.FolderOpen) 1f else 0f }) {
                    OverlayContent(
                        overlay = overlay,
                        model = model,
                        layout = layout,
                        cfg = cfg,
                        active = top,
                        open = ::open,
                        close = ::closeTop,
                        closeAll = { overlays.clear() },
                        startMove = { key -> overlays.clear(); moving = key },
                    )
                }
            }

            if (reveal.value < 1f) StartupMark(loading = !ready, alpha = { 1f - reveal.value })
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
    val cells = remember(layout.grid) { layout.grid.map<GridItem, Cell> { Cell.Item(it) } + Cell.Settings }
    val rows = remember(cells, m.columns) { cells.chunked(m.columns) }
    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        contentPadding = PaddingValues(bottom = 160.dp),
        modifier = modifier.fillMaxSize().testTag("home"),
    ) {
        item(key = "shelf") {
            Box(Modifier.fillMaxWidth().height(m.trayTopAtRest), contentAlignment = Alignment.BottomCenter) {
                if (showHint) ShelfHint(hintAlpha)
            }
        }
        item(key = "dock") {
            DockTray(
                apps = layout.dock,
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
    val palette = LocalPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(bottom = 10.dp).graphicsLayer { this.alpha = alpha() },
    ) {
        Text("⌃", style = Type.secondary.copy(shadow = Type.shadow), color = palette.primary.copy(alpha = 0.85f))
        Text("Press up for full screen", style = Type.caption.copy(shadow = Type.shadow), color = palette.primary.copy(alpha = 0.75f))
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
            .padding(start = m.inset - m.trayMargin, end = m.inset - m.trayMargin, top = m.trayPadVertical, bottom = m.trayPadVertical),
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
                        showLabel = false,
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
) {
    val art = rememberArt(model, app)
    val cfg by model.config.collectAsStateWithLifecycle()
    var bounds by remember { mutableStateOf<Rect?>(null) }
    TileWithLabel(
        label = app.label,
        tag = "app:${app.packageName}",
        moving = moving,
        isNew = model.isNew(app.packageName, cfg),
        focusRequester = focusRequester,
        onFocused = onFocused,
        onClick = { if (launch != null) launch(app, bounds) else model.launch(app) },
        onMenu = onMenu,
        showLabel = showLabel,
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
    TileWithLabel(
        label = item.folder.name,
        tag = "folder:${item.folder.id}",
        moving = moving,
        isNew = false,
        focusRequester = focusRequester,
        onFocused = onFocused,
        onClick = onOpen,
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
    glassBackground: Boolean = false,
    showLabel: Boolean = true,
    tileModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val m = LocalMetrics.current
    var focused by remember { mutableStateOf(false) }
    val labelAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(140), label = "label")
    Column {
    Box {
        FocusTile(
            label = label,
            onClick = onClick,
            onLongClick = onMenu,
            wiggle = moving,
            shadow = !glassBackground,
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
        if (showLabel || isNew) {
            // Sits in the row gap below the tile so labels never change the layout.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = 12.dp.toPx() + size.height
                        alpha = labelAlpha
                    },
            ) {
                if (isNew) Box(Modifier.size(6.dp).background(palette.accent, CircleShape))
                Text(
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

@Composable
fun rememberArt(model: HomeModel, app: AppEntry): ImageBitmap? {
    val context = LocalContext.current
    val graph = context.app
    val cfg by model.config.collectAsStateWithLifecycle()
    val spec = model.spec(app, cfg)
    val art by produceState(graph.tileArt.peek(spec), spec) { value = graph.tileArt.load(spec) }
    return art
}

/** tvOS shows guidance while rearranging; without it the wiggle mode feels like a dead end. */
@Composable
private fun MoveBanner(key: String, layout: HomeLayout, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val inDock = layout.dock.any { appKey(it.packageName) == key }
    val hint = if (inDock) "◀ ▶  Rearrange   ·   ▼  Move to Apps   ·   Select  Done"
    else "◀ ▶ ▲ ▼  Move   ·   ▲ on first row  Add to Top Row   ·   Select  Done"
    Box(
        modifier
            .padding(bottom = Safe.bottom)
            .glass(LocalBackdrop.current, Shapes.pill, GlassStyle.panel(palette.light))
            .padding(horizontal = 28.dp, vertical = 14.dp)
            .testTag("move-banner"),
    ) {
        Text(hint, style = Type.secondary, color = palette.primary)
    }
}
