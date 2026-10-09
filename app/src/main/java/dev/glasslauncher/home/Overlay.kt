package dev.glasslauncher.home

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.data.Folder
import dev.glasslauncher.glass.Blur
import dev.glasslauncher.glass.WallpaperLoader
import dev.glasslauncher.ui.GlassBox
import dev.glasslauncher.glass.glass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Overlay {
    /** [anchor]: the tile's bounds in the window, so the menu opens beside it (tvOS context menu). */
    data class AppMenu(val app: AppEntry, val inDock: Boolean, val folderId: String?, val anchor: androidx.compose.ui.geometry.Rect? = null) : Overlay
    data class MoveTo(val app: AppEntry, val inDock: Boolean, val folderId: String?, val anchor: androidx.compose.ui.geometry.Rect?) : Overlay
    data class FolderMenu(val folder: Folder) : Overlay
    /** [anchor]: the folder tile's bounds, so the panel grows out of it. */
    data class FolderOpen(val folderId: String, val anchor: androidx.compose.ui.geometry.Rect? = null) : Overlay
    data class FolderPicker(val app: AppEntry) : Overlay
    data class IconPicker(val app: AppEntry) : Overlay
    data class TextInput(
        val title: String,
        val initial: String,
        val hint: String = "",
        val onDone: (String) -> Unit,
    ) : Overlay
    data object Settings : Overlay
    data object Tips : Overlay
    data object ControlCenter : Overlay
    data object AppSwitcher : Overlay
    /** Fire TV's own settings, as a top-level list (the stock home screen normally provides it). */
    data object TvSettings : Overlay
    /** tvOS's confirmation card: [confirm] first and focused (red when [destructive]), then Cancel. */
    data class Confirm(
        val title: String,
        val message: String,
        val confirm: String,
        val destructive: Boolean = false,
        val onConfirm: () -> Unit,
    ) : Overlay
    data class PhoneSetup(
        val title: String,
        val fields: List<dev.glasslauncher.system.PhoneField>,
        val onSubmit: (Map<String, String>) -> Unit,
    ) : Overlay
}

/** Two blurs of the screen from one capture: frosted (overlay glass) and soft (dock-like, behind Control Center). */
class OverlayShots(val frosted: ImageBitmap?, val soft: ImageBitmap?, val sharp: ImageBitmap? = null)

/**
 * [keepSharp]: also return the capture itself (Control Center fades from it to the blur, over a hidden Home).
 * PixelCopy reads the window's last frame on the compositor's side, asynchronously: GraphicsLayer.toImageBitmap
 * re-rendered Home on the main thread (50-90 ms), which made every overlay open stall (folders: 80% janky).
 */
suspend fun captureOverlay(view: android.view.View, light: Boolean, keepSharp: Boolean = false): OverlayShots = runCatching {
    val window = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().first().window
    val w = if (keepSharp) view.width else WallpaperLoader.CLEAR_W
    val h = if (keepSharp) view.height else WallpaperLoader.CLEAR_H
    val shot = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val ok = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
        runCatching {
            android.view.PixelCopy.request(window, shot, { cont.resumeWith(Result.success(it == android.view.PixelCopy.SUCCESS)) }, android.os.Handler(android.os.Looper.getMainLooper()))
        }.onFailure { cont.resumeWith(Result.success(false)) }
    }
    if (!ok) { shot.recycle(); return@runCatching OverlayShots(null, null) }
    withContext(Dispatchers.Default) {
        // The dock's clear blur (480×270, small radius): the screen stays recognisable, just out of focus.
        val soft = Blur.backdrop(shot, WallpaperLoader.CLEAR_W, WallpaperLoader.CLEAR_H, radius = 3, saturation = 1.15f)
        val softGpu = if (soft.getPixel(soft.width / 2, soft.height / 2) ushr 24 == 0) null
            else (soft.copy(android.graphics.Bitmap.Config.HARDWARE, false)?.also { soft.recycle() } ?: soft).asImageBitmap()
        val sharp = if (keepSharp && softGpu != null) (shot.copy(android.graphics.Bitmap.Config.HARDWARE, false) ?: shot).asImageBitmap() else null
        val frosted = blurFrosted(if (sharp != null) shot.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { shot.recycle() } else shot, light)
        OverlayShots(frosted, softGpu, sharp)
    }
}.getOrDefault(OverlayShots(null, null))

/** Captures what's on screen, blurs it once, and returns it as the backdrop for overlay glass. */
suspend fun captureBlurred(layer: GraphicsLayer, light: Boolean? = null): ImageBitmap? = runCatching {
    val shot = layer.toImageBitmap().asAndroidBitmap()
    withContext(Dispatchers.Default) { blurFrosted(shot.copy(android.graphics.Bitmap.Config.ARGB_8888, false), light) }
}.getOrNull()

private fun blurFrosted(soft: android.graphics.Bitmap, light: Boolean?): ImageBitmap? {
        val blurred = Blur.backdrop(soft, WallpaperLoader.BLUR_W, WallpaperLoader.BLUR_H, radius = 6).also { soft.recycle() }
        // A capture taken before the first real frame is blank; returning null falls back to the wallpaper blur.
        return if (blurred.getPixel(blurred.width / 2, blurred.height / 2) ushr 24 == 0) null
        else (blurred.also { if (light != null) Blur.legible(it, light) }.copy(android.graphics.Bitmap.Config.HARDWARE, false)?.also { blurred.recycle() } ?: blurred).asImageBitmap()
}

/**
 * The active overlay keeps D-pad focus inside it; overlays stacked underneath refuse focus entirely,
 * so focus can't wander onto hidden menus or the home screen.
 */
fun Modifier.trapFocus(active: Boolean): Modifier =
    focusProperties { if (active) onExit = { cancelFocusChange() } else onEnter = { cancelFocusChange() } }.focusGroup()

/** Right-hand glass panel that slides in, used for menus and settings. */
@Composable
fun SidePanel(active: Boolean, width: Dp = 420.dp, content: @Composable BoxScope.() -> Unit) {
    val enter = rememberOverlayEnter()
    val reduceMotion = dev.glasslauncher.ui.LocalUiPrefs.current.reduceMotion
    // No dimming behind it (tvOS doesn't dim for these, and a full-screen scrim is an extra GPU pass).
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = enter.value },
    ) {
        GlassBox(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(18.dp)
                .width(width)
                .fillMaxHeight()
                .graphicsLayer { translationX = OverlayMotion.slide(enter.value, reduceMotion) * 80.dp.toPx() }
                .trapFocus(active),
            content = content,
        )
    }
}

/** How overlays move in: a slide or slight grow, or with Reduce Motion only a fade. */
object OverlayMotion {
    fun scale(enter: Float, reduceMotion: Boolean) = if (reduceMotion) 1f else 0.94f + 0.06f * enter
    fun slide(enter: Float, reduceMotion: Boolean) = if (reduceMotion) 0f else 1f - enter
}

/** Fades and scales a full-screen overlay in. */
@Composable
fun FullOverlay(active: Boolean, content: @Composable BoxScope.() -> Unit) {
    val enter = rememberOverlayEnter()
    val reduceMotion = dev.glasslauncher.ui.LocalUiPrefs.current.reduceMotion
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = enter.value
                val s = OverlayMotion.scale(enter.value, reduceMotion)
                scaleX = s; scaleY = s
            }
            .trapFocus(active),
        content = content,
    )
}

/**
 * The screen behind an overlay, out of focus: a snapshot of Home taken as the overlay opened, with [blurred]
 * fading in over it, and Home itself not drawn meanwhile. Two cheap images instead of Home's whole tree
 * plus glass over it, which made opening Control Center or a folder ~80% janky on the stick.
 */
@Composable
fun SnapshotBackdrop(enter: () -> Float, blurred: ImageBitmap?, fallbackGlass: Boolean = false) {
    val state = dev.glasslauncher.glass.LocalBackdrop.current
    val sharp = state.overlaySharp
    // Separate effects: swapping [blurred] (a darkened copy arriving a frame later) must not drop the
    // snapshot, or Home would be drawn under the overlay for the rest of its life.
    androidx.compose.runtime.DisposableEffect(sharp) {
        onDispose { if (state.overlaySharp === sharp) state.overlaySharp = null }
    }
    val hide = sharp != null && blurred != null
    androidx.compose.runtime.DisposableEffect(hide) {
        if (hide) state.homeHidden = true
        onDispose { state.homeHidden = false }
    }
    if (blurred == null) {
        // No capture (it failed, or the JVM test harness): frosted glass over the live Home, as before.
        if (fallbackGlass) Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = enter() }
                .glass(state, androidx.compose.ui.graphics.RectangleShape, dev.glasslauncher.glass.GlassStyle.overlay(dev.glasslauncher.ui.LocalPalette.current.light).copy(highlight = 0f, rim = 0f)),
        )
        return
    }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val dst = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
        val e = enter()
        if (sharp != null && e < 1f) drawImage(sharp, dstSize = dst)
        drawImage(blurred, dstSize = dst, alpha = e, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
    }
}

val Scrim = Color(0x59000000)

/**
 * True while an overlay is leaving: it stays composed, without focus, for its exit animation
 * (tvOS: exits mirror entrances, nothing vanishes in one frame), then Home removes it.
 */
val LocalOverlayExiting = androidx.compose.runtime.compositionLocalOf { false }

/** The last tile that asked for its menu: tiles write their bounds here just before opening one. */
class MenuAnchor { var bounds: androidx.compose.ui.geometry.Rect? = null }
val LocalMenuAnchor = androidx.compose.runtime.staticCompositionLocalOf { MenuAnchor() }

/** The muted icon in Settings' left column, one per page (Settings' own gear for the main list). */
@Composable
private fun SettingsPageIcon(title: String) {
    val palette = dev.glasslauncher.ui.LocalPalette.current
    // Pages without their own (the main page, Fire TV's) get the gear, drawn the same muted way.
    val res = SETTINGS_ICONS[title] ?: dev.glasslauncher.R.drawable.ic_settings
    Box(Modifier.testTag("settings-icon:${title.ifEmpty { "Settings" }}")) {
        // Just the icon, muted: no tile or border behind it.
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(res), null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(palette.primary.copy(alpha = 0.35f)),
            modifier = Modifier.size(120.dp),
        )
    }
}

private val SETTINGS_ICONS = mapOf(
    "Appearance" to dev.glasslauncher.R.drawable.ic_dark_mode,
    "Display & Text" to dev.glasslauncher.R.drawable.ic_format_size,
    "Font" to dev.glasslauncher.R.drawable.ic_format_size,
    "Text Size" to dev.glasslauncher.R.drawable.ic_format_size,
    "Control Center" to dev.glasslauncher.R.drawable.ic_tune,
    "Top Shelf Content" to dev.glasslauncher.R.drawable.ic_tv,
    "Hidden Apps" to dev.glasslauncher.R.drawable.ic_visibility_off,
    "Hide Apps" to dev.glasslauncher.R.drawable.ic_visibility_off,
    "Icon Pack" to dev.glasslauncher.R.drawable.ic_apps,
    "Screen Saver" to dev.glasslauncher.R.drawable.ic_landscape,
    "Current Selection" to dev.glasslauncher.R.drawable.ic_landscape,
    "Start After" to dev.glasslauncher.R.drawable.ic_landscape,
    "Aerials" to dev.glasslauncher.R.drawable.ic_landscape,
    "Slideshow" to dev.glasslauncher.R.drawable.ic_photo_library,
    "Choose Photos" to dev.glasslauncher.R.drawable.ic_photo_library,
    "Widgets" to dev.glasslauncher.R.drawable.ic_widgets,
    "Accessibility" to dev.glasslauncher.R.drawable.ic_accessibility,
    "Home Button" to dev.glasslauncher.R.drawable.ic_home,
    "Remote Buttons" to dev.glasslauncher.R.drawable.ic_settings_remote,
    "Updates" to dev.glasslauncher.R.drawable.ic_system_update,
    "Backup & Restore" to dev.glasslauncher.R.drawable.ic_backup,
    "Glass TV Launcher" to dev.glasslauncher.R.drawable.ic_info,
    "Root" to dev.glasslauncher.R.drawable.ic_code,
    "App Freezer" to dev.glasslauncher.R.drawable.ic_code,
    "Root Log" to dev.glasslauncher.R.drawable.ic_code,
)

/** 0 → 1 on entrance and back to 0 on exit, with the overlay curve (~130 ms each way). */
@Composable
fun rememberOverlayEnter(): Animatable<Float, androidx.compose.animation.core.AnimationVector1D> {
    val enter = remember { Animatable(0f) }
    val exiting = LocalOverlayExiting.current
    LaunchedEffect(exiting) { enter.animateTo(if (exiting) 0f else 1f, dev.glasslauncher.ui.Motion.overlay()) }
    return enter
}

/** Scroll only as far as needed to reveal the focused row (Compose's TV default pivots every row to 30%). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
object MinimalScroll : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing <= containerSize -> 0f
            offset < 0 && trailing > containerSize -> 0f
            kotlin.math.abs(offset) < kotlin.math.abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
    }
}

/** Collects the current page's title so a full-page layout can centre it at the top (SYS-02). */
/**
 * What a Settings page tells its frame: the title (centred at the top) and its explanations, which tvOS
 * puts on the left under the page's icon (the right side is only rows). Keyed by page, so during a page
 * push only the arriving page's words show.
 */
class TitleSink {
    var title by androidx.compose.runtime.mutableStateOf("")
    var page by androidx.compose.runtime.mutableStateOf<Any?>(null)
    val captions = androidx.compose.runtime.mutableStateListOf<Pair<Any?, String>>()
    /** A page that needs the whole width (Choose Aerials): the frame drops the icon column while it shows. */
    var wide by androidx.compose.runtime.mutableStateOf<Any?>(null)
    /** The focused row's one-line help (keyed by page): what the left column shows while it has focus. */
    var help by androidx.compose.runtime.mutableStateOf<Pair<Any?, String>?>(null)
}

/** The Settings page a composable belongs to (its captions are kept apart from the page it replaces). */
val LocalPageKey = androidx.compose.runtime.staticCompositionLocalOf<Any?> { null }

/** Settings pages ask for a confirmation card (Restart, Restore…) through this. */
val LocalConfirm = androidx.compose.runtime.staticCompositionLocalOf<(Overlay.Confirm) -> Unit> { {} }
val LocalTitleSink = androidx.compose.runtime.staticCompositionLocalOf<TitleSink?> { null }

/**
 * SYS-02: Settings as a full page over the blurred Home: a centred grey title, a large glass icon in
 * the left third, and the list on the right.
 */
@Composable
fun SettingsPage(active: Boolean, content: @Composable BoxScope.() -> Unit) {
    val palette = dev.glasslauncher.ui.LocalPalette.current
    val sink = remember { TitleSink() }
    FullOverlay(active) {
        // A calm washed page, not glass over Home: through the glass, the grid and the shelf's giant
        // wordmark ghosted behind the rows (audit 9.3). Tinted faintly by the scene's own colour.
        val page = if (palette.light) listOf(androidx.compose.ui.graphics.Color(0xFFF2F2F8), androidx.compose.ui.graphics.Color(0xFFE3E6F0))
            else listOf(androidx.compose.ui.graphics.Color(0xFF22252E), androidx.compose.ui.graphics.Color(0xFF111217))
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(page)))
        androidx.tv.material3.Text(
            sink.title,
            style = dev.glasslauncher.ui.Type.title,
            color = palette.primary,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp),
        )
        val wide = sink.wide != null && sink.wide == sink.page
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().padding(top = 78.dp, start = 45.dp, end = 45.dp, bottom = 20.dp)) {
            // tvOS 27: the page's muted icon, and under it the page's explanations; only rows on the right.
            if (!wide) androidx.compose.foundation.layout.Column(
                Modifier.weight(0.42f).fillMaxHeight().padding(end = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SettingsPageIcon(sink.title) }
                // One purposeful line: the focused row's help, else the page's own caption. Never a pile.
                val words = sink.help?.takeIf { it.first == sink.page }?.second
                    ?: sink.captions.firstOrNull { it.first == sink.page }?.second
                SettingsHelp(words)
            }
            Box(Modifier.weight(if (wide) 1f else 0.58f).fillMaxHeight()) {
                androidx.compose.runtime.CompositionLocalProvider(LocalTitleSink provides sink) {
                    // The list fades out at its bottom edge instead of being cut off there.
                    Box(Modifier.fillMaxSize().fadeBottom(36.dp).trapFocus(active), content = content)
                }
            }
        }
    }
}

/**
 * The words under a Settings page's icon. The slot is a fixed height whatever the text (a longer help, or a
 * font with taller lines, overflows below it rather than growing it), so the icon above never moves as the text
 * changes. A change is a cross-fade in place. Both texts fill the slot's full width and centre inside it: with
 * wrap-content widths the container was as wide as the wider text, the narrower one sat at its left edge, and
 * when the old text left the container narrowed and re-centred, which read as the text sliding left to right.
 */
@Composable
internal fun SettingsHelp(words: String?) {
    val palette = dev.glasslauncher.ui.LocalPalette.current
    val reduceMotion = dev.glasslauncher.ui.LocalUiPrefs.current.reduceMotion
    Box(Modifier.fillMaxWidth().padding(bottom = 18.dp).height(60.dp).testTag("settings-help"), contentAlignment = Alignment.TopCenter) {
        androidx.compose.animation.AnimatedContent(
            targetState = words,
            modifier = Modifier.fillMaxWidth().wrapContentHeight(align = Alignment.Top, unbounded = true),
            transitionSpec = {
                val ms = if (reduceMotion) 0 else HELP_FADE_MS
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(ms)) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(ms)))
                    // No size animation: the default one clips and animates the height, which wipes a
                    // multi-line text in from the top instead of just fading it.
                    .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() })
            },
            contentAlignment = Alignment.TopCenter,
            label = "settings-help",
        ) { w ->
            if (w != null) androidx.tv.material3.Text(
                w, style = dev.glasslauncher.ui.Type.secondary, color = palette.secondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                // Bounded: more than this would run off the bottom of the screen.
                maxLines = HELP_MAX_LINES, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val HELP_FADE_MS = 160
private const val HELP_MAX_LINES = 4

/** Fades content to transparent over its last [height] (an offscreen layer masked by a gradient). */
fun Modifier.fadeBottom(height: androidx.compose.ui.unit.Dp): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = height.toPx()
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Transparent), startY = size.height - h, endY = size.height),
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

/**
 * A compact glass menu beside its tile, like the tvOS context menu: to the right of the tile when it
 * fits, else to the left, vertically centred on it. It grows out of the tile edge (~130 ms) and
 * shrinks back on close. No dimming behind it.
 */
@Composable
fun AnchoredMenu(
    active: Boolean,
    anchor: androidx.compose.ui.geometry.Rect?,
    width: Dp = 270.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.(androidx.compose.ui.focus.FocusRequester) -> Unit,
) {
    val enter = rememberOverlayEnter()
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(active) { if (active) { androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } } }
    val originX = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.ui.layout.Layout(
        content = {
            GlassBox(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                modifier = Modifier
                    .width(width)
                    .graphicsLayer {
                        val s = 0.85f + 0.15f * enter.value
                        scaleX = s; scaleY = s
                        alpha = enter.value
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(originX.floatValue, 0.5f)
                    }
                    .trapFocus(active),
            ) {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(10.dp),
                ) { content(first) }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, c ->
        val panel = measurables.first().measure(androidx.compose.ui.unit.Constraints(maxWidth = c.maxWidth, maxHeight = c.maxHeight))
        val gap = 22.dp.roundToPx()
        val margin = 24.dp.roundToPx()
        // The focused tile is drawn 1.2x around its centre; keep clear of that, not the layout box.
        val a = anchor?.let {
            val dx = it.width * 0.1f; val dy = it.height * 0.1f
            androidx.compose.ui.geometry.Rect(it.left - dx, it.top - dy, it.right + dx, it.bottom + dy)
        }
        val (x, y) = if (a == null) {
            originX.floatValue = 0.5f
            (c.maxWidth - panel.width) / 2 to (c.maxHeight - panel.height) / 2
        } else {
            val right = a.right.toInt() + gap
            val fitsRight = right + panel.width <= c.maxWidth - margin
            originX.floatValue = if (fitsRight) 0f else 1f
            val px = if (fitsRight) right else (a.left.toInt() - gap - panel.width).coerceAtLeast(margin)
            val py = (a.center.y - panel.height / 2f).toInt().coerceIn(margin, (c.maxHeight - margin - panel.height).coerceAtLeast(margin))
            px to py
        }
        layout(c.maxWidth, c.maxHeight) { panel.place(x, y) }
    }
}
