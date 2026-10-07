package dev.glasslauncher.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.glasslauncher.app
import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.ThemeMode
import dev.glasslauncher.data.appKey
import dev.glasslauncher.featured.TopShelf
import dev.glasslauncher.glass.BackdropState
import dev.glasslauncher.glass.GlassStyle
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.glass
import dev.glasslauncher.settings.SettingsPanel
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.KeyDirection
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Palette
import dev.glasslauncher.ui.LocalUiPrefs
import dev.glasslauncher.ui.UiPrefs
import dev.glasslauncher.ui.Safe
import androidx.tv.material3.LocalTextStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.glasslauncher.dream.AerialActivity
import kotlinx.coroutines.delay
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type
import dev.glasslauncher.widgets.IdleState
import dev.glasslauncher.widgets.StatusBar
import dev.glasslauncher.widgets.rememberIdleState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private val SidePadding = Safe.horizontal
private val ColumnGap = 20.dp
private val RowGap = 6.dp
private val ShelfHeight = 312.dp

@OptIn(ExperimentalFoundationApi::class)
private object NoAutoScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(model: HomeModel, homePresses: Flow<Unit>) {
    val context = LocalContext.current
    val graph = context.app
    val cfg by model.config.collectAsStateWithLifecycle()
    val layout by model.layout.collectAsStateWithLifecycle()
    val backdrop = remember { BackdropState() }
    val dark = when (cfg.theme) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val wallpaper = if (dark) cfg.wallpaperDark else cfg.wallpaperLight
    LaunchedEffect(wallpaper) { backdrop.backdrop = graph.wallpapers.load(wallpaper) }
    val prefs = remember(cfg) { UiPrefs.resolve(context, cfg) }
    val palette = remember(backdrop.backdrop, dark, prefs) { Palette(light = backdrop.backdrop?.isLight ?: !dark, highContrast = prefs.highContrast) }
    backdrop.reduceTransparency = prefs.reduceTransparency

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val overlays = remember { mutableStateListOf<Overlay>() }
    var moving by remember { mutableStateOf<String?>(null) }
    var lastFocused by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val layer = rememberGraphicsLayer()
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val idle = rememberIdleState(cfg.idleFadeMinutes)

    fun open(overlay: Overlay) {
        scope.launch {
            if (overlays.isEmpty()) backdrop.overlay = captureBlurred(layer)
            overlays.add(overlay)
        }
    }
    fun closeTop() {
        overlays.removeLastOrNull()
    }
    fun firstKey(): String? = layout.dock.firstOrNull()?.let { appKey(it.packageName) } ?: layout.grid.firstOrNull()?.key

    suspend fun focusKey(key: String?) {
        key ?: return
        withFrameNanos { }
        runCatching { requester(key).requestFocus() }
    }

    suspend fun scrollToTop() {
        if (listState.firstVisibleItemIndex == 0) {
            listState.animateScrollBy(-listState.firstVisibleItemScrollOffset.toFloat(), spring(stiffness = Spring.StiffnessMediumLow))
        } else listState.animateScrollToItem(0)
    }

    fun onRowFocused(index: Int) {
        scope.launch {
            launch {
                val target = if (index >= 2) 1f else 0f
                if (prefs.reduceMotion) backdrop.wallpaperBlur.snapTo(target) else backdrop.wallpaperBlur.animateTo(target, tween(350))
            }
            if (index <= 1) scrollToTop() else {
                val target = with(density) { 64.dp.toPx() }
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (info != null) listState.animateScrollBy(info.offset - target, spring(stiffness = Spring.StiffnessMediumLow))
                else listState.animateScrollToItem(index, -target.toInt())
            }
        }
    }
    fun onShelfFocused() = onRowFocused(0)

    // Initial focus once apps are known; refocus whatever was focused when overlays close.
    LaunchedEffect(layout.loaded) {
        if (!layout.loaded) return@LaunchedEffect
        focusKey(firstKey())
        if (!cfg.tipsSeen) {
            delay(1500) // let the wallpaper and shelf draw so the card's glass has something to blur
            open(Overlay.Tips)
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
                focusKey(lastFocused ?: firstKey())
            }
        }
    }
    LaunchedEffect(layout, moving) { moving?.let { focusKey(it) } }
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
            scrollToTop()
            focusKey(firstKey())
        }
    }

    BackHandler {
        when {
            moving != null -> moving = null
            overlays.isNotEmpty() -> closeTop()
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 -> scope.launch {
                scrollToTop(); focusKey(firstKey())
            }
        }
    }

    CompositionLocalProvider(
        LocalBackdrop provides backdrop,
        LocalPalette provides palette,
        LocalUiPrefs provides prefs,
        LocalTextStyle provides Type.body,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .onSizeChanged { backdrop.rootSize = it }
                .onPreviewKeyEvent { ev ->
                    val e = ev.nativeKeyEvent
                    idle.touch()
                    if (e.action == AndroidKeyEvent.ACTION_DOWN) when (e.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { KeyDirection.dx = -1; KeyDirection.dy = 0 }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { KeyDirection.dx = 1; KeyDirection.dy = 0 }
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> { KeyDirection.dx = 0; KeyDirection.dy = -1 }
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { KeyDirection.dx = 0; KeyDirection.dy = 1 }
                    }
                    val key = moving
                    if (key == null || overlays.isNotEmpty()) return@onPreviewKeyEvent false
                    if (e.action == AndroidKeyEvent.ACTION_DOWN) handleMoveKey(e.keyCode, key, layout, model) { moving = null }
                    true
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = idle.shift.x; translationY = idle.shift.y }
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) {
                WallpaperLayer(backdrop, palette)
                run {
                    // Lives outside the list so it stays composed; follows the scroll and fades in the draw phase only.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(ShelfHeight)
                            .graphicsLayer {
                                val shelfPx = ShelfHeight.toPx()
                                translationY = -(if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else shelfPx)
                                alpha = idle.chromeAlpha * (1f - backdrop.wallpaperBlur.value * 3f).coerceIn(0f, 1f)
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                    ) {
                        TopShelf(cfg = cfg, modifier = Modifier.fillMaxSize(), paused = overlays.isNotEmpty() || idle.idle || moving != null, onFocused = { if (it) onShelfFocused() })
                    }
                }
                CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
                    HomeList(
                        layout = layout,
                        cfg = cfg,
                        model = model,
                        listState = listState,
                        moving = moving,
                        idle = idle,
                        requester = ::requester,
                        onFocused = { lastFocused = it },
                        onRowFocused = ::onRowFocused,
                        onAppMenu = { app, inDock -> open(Overlay.AppMenu(app, inDock, null)) },
                        onFolderOpen = { open(Overlay.FolderOpen(it)) },
                        onFolderMenu = { open(Overlay.FolderMenu(it)) },
                    )
                }
            }

            StatusBar(
                cfg = cfg,
                idle = idle,
                onSettings = { open(Overlay.Settings) },
                modifier = Modifier.align(Alignment.TopEnd),
                fade = { 1f - backdrop.wallpaperBlur.value },
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
        }
    }
}

private fun handleMoveKey(keyCode: Int, key: String, layout: HomeLayout, model: HomeModel, done: () -> Unit) {
    val pkg = key.removePrefix("app:")
    val inDock = key.startsWith("app:") && layout.dock.any { it.packageName == pkg }
    val gridIndex = layout.grid.indexOfFirst { it.key == key }
    when (keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> model.move(key, -1)
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> model.move(key, 1)
        AndroidKeyEvent.KEYCODE_DPAD_UP -> when {
            inDock -> Unit
            gridIndex in 0 until COLUMNS && key.startsWith("app:") -> model.moveIntoDock(pkg)
            else -> model.move(key, -COLUMNS)
        }
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> when {
            inDock -> model.moveOutOfDock(pkg)
            !model.move(key, COLUMNS) -> model.move(key, layout.grid.lastIndex - gridIndex)
        }
        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_BACK -> done()
    }
}

@Composable
private fun WallpaperLayer(state: BackdropState, palette: Palette) {
    Canvas(Modifier.fillMaxSize()) {
        val b = state.backdrop ?: return@Canvas
        val dst = IntSize(size.width.toInt(), size.height.toInt())
        val blur = state.wallpaperBlur.value
        if (blur < 0.999f) drawImage(b.sharp, dstSize = dst, filterQuality = FilterQuality.Low)
        if (blur > 0.001f) drawImage(b.blurred, dstSize = dst, alpha = blur, filterQuality = FilterQuality.Low)
    }
}

@Composable
private fun HomeList(
    layout: HomeLayout,
    cfg: LauncherConfig,
    model: HomeModel,
    listState: LazyListState,
    moving: String?,
    idle: IdleState,
    requester: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
    onRowFocused: (Int) -> Unit,
    onAppMenu: (AppEntry, Boolean) -> Unit,
    onFolderOpen: (String) -> Unit,
    onFolderMenu: (dev.glasslauncher.data.Folder) -> Unit,
) {
    val rows = remember(layout.grid) { layout.grid.chunked(COLUMNS) }
    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        contentPadding = PaddingValues(bottom = 120.dp),
        modifier = Modifier.fillMaxSize().testTag("home"),
    ) {
        item(key = "shelf") { Spacer(Modifier.fillMaxWidth().height(ShelfHeight)) }
        item(key = "dock") {
            DockShelf(
                apps = layout.dock,
                model = model,
                moving = moving,
                requester = requester,
                onFocused = onFocused,
                onAppMenu = { onAppMenu(it, true) },
                modifier = Modifier.onFocusChanged { if (it.hasFocus) onRowFocused(1) },
            )
        }
        itemsIndexed(rows, key = { i, _ -> "row-$i" }) { i, row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ColumnGap),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SidePadding, vertical = RowGap)
                    .onFocusChanged { if (it.hasFocus) onRowFocused(i + 2) },
            ) {
                row.forEach { item ->
                    Box(Modifier.weight(1f)) {
                        when (item) {
                            is GridItem.App -> AppCell(
                                app = item.app,
                                model = model,
                                moving = moving == item.key,
                                focusRequester = requester(item.key),
                                onFocused = { onFocused(item.key) },
                                onMenu = { onAppMenu(item.app, false) },
                            )
                            is GridItem.FolderItem -> FolderCell(
                                item = item,
                                model = model,
                                moving = moving == item.key,
                                focusRequester = requester(item.key),
                                onFocused = { onFocused(item.key) },
                                onOpen = { onFolderOpen(item.folder.id) },
                                onMenu = { onFolderMenu(item.folder) },
                            )
                        }
                    }
                }
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DockShelf(
    apps: List<AppEntry>,
    model: HomeModel,
    moving: String?,
    requester: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
    onAppMenu: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = SidePadding - 14.dp)
            .glass(LocalBackdrop.current, RoundedCornerShape(26.dp), GlassStyle.shelf(palette.light))
            .padding(start = 14.dp, end = 14.dp, top = 14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ColumnGap), modifier = Modifier.fillMaxWidth().testTag("dock")) {
            apps.forEach { app ->
                Box(Modifier.weight(1f)) {
                    val key = appKey(app.packageName)
                    AppCell(
                        app = app,
                        model = model,
                        moving = moving == key,
                        focusRequester = requester(key),
                        onFocused = { onFocused(key) },
                        onMenu = { onAppMenu(app) },
                    )
                }
            }
            repeat(DOCK_SIZE - apps.size) { Spacer(Modifier.weight(1f)) }
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
    onClick: () -> Unit = { model.launch(app) },
) {
    val art = rememberArt(model, app)
    TileWithLabel(
        label = app.label,
        tag = "app:${app.packageName}",
        moving = moving,
        focusRequester = focusRequester,
        onFocused = onFocused,
        onClick = onClick,
        onMenu = onMenu,
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
        focusRequester = focusRequester,
        onFocused = onFocused,
        onClick = onOpen,
        onMenu = onMenu,
        glassBackground = true,
    ) {
        // Up to six mini tiles, three per row, centred like a tvOS folder.
        Column(
            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            item.apps.take(6).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { app ->
                        val art = rememberArt(model, app)
                        Box(Modifier.fillMaxWidth(0.3f).aspectRatio(16f / 9f).graphicsLayer { shape = RoundedCornerShape(5.dp); clip = true }) {
                            art?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TileWithLabel(
    label: String,
    tag: String,
    moving: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    glassBackground: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val labelAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "label")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FocusTile(
            label = label,
            onClick = onClick,
            onLongClick = onMenu,
            wiggle = moving,
            shadow = !glassBackground,
            onFocusChange = { focused = it; if (it) onFocused() },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .testTag(tag)
                .then(
                    if (glassBackground) Modifier.glass(LocalBackdrop.current, Shapes.tile, GlassStyle.shelf(palette.light))
                    else Modifier,
                ),
        ) { content() }
        Text(
            text = label,
            style = Type.label,
            color = palette.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 12.dp, bottom = 4.dp)
                .graphicsLayer {
                    alpha = labelAlpha
                    translationY = (1f - labelAlpha) * -6.dp.toPx()
                },
        )
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
