package dev.glasslauncher.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    data class AppMenu(val app: AppEntry, val inDock: Boolean, val folderId: String?) : Overlay
    data class FolderMenu(val folder: Folder) : Overlay
    data class FolderOpen(val folderId: String) : Overlay
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
    data class PhoneSetup(
        val title: String,
        val fields: List<dev.glasslauncher.system.PhoneField>,
        val onSubmit: (Map<String, String>) -> Unit,
    ) : Overlay
}

/** Captures what's on screen, blurs it once, and returns it as the backdrop for overlay glass. */
suspend fun captureBlurred(layer: GraphicsLayer): ImageBitmap? = runCatching {
    val shot = layer.toImageBitmap().asAndroidBitmap()
    withContext(Dispatchers.Default) {
        val soft = shot.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        val blurred = Blur.backdrop(soft, WallpaperLoader.BLUR_W, WallpaperLoader.BLUR_H, radius = 6).also { soft.recycle() }
        // A capture taken before the first real frame is blank; returning null falls back to the wallpaper blur.
        if (blurred.getPixel(blurred.width / 2, blurred.height / 2) ushr 24 == 0) null
        else (blurred.copy(android.graphics.Bitmap.Config.HARDWARE, false)?.also { blurred.recycle() } ?: blurred).asImageBitmap()
    }
}.getOrNull()

/**
 * The active overlay keeps D-pad focus inside it; overlays stacked underneath refuse focus entirely,
 * so focus can't wander onto hidden menus or the home screen.
 */
fun Modifier.trapFocus(active: Boolean): Modifier =
    focusProperties { if (active) onExit = { cancelFocusChange() } else onEnter = { cancelFocusChange() } }.focusGroup()

/** Right-hand glass panel that slides in, used for menus and settings. */
@Composable
fun SidePanel(active: Boolean, width: Dp = 420.dp, content: @Composable BoxScope.() -> Unit) {
    val enter = remember { Animatable(0f) }
    // MOTION-02: system surfaces appear in well under 100 ms.
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(90)) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = enter.value },
    ) {
        Box(Modifier.fillMaxSize().background(Scrim))
        GlassBox(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(18.dp)
                .width(width)
                .fillMaxHeight()
                .graphicsLayer { translationX = (1f - enter.value) * 80.dp.toPx() }
                .trapFocus(active),
            content = content,
        )
    }
}

/** Fades and scales a full-screen overlay in. */
@Composable
fun FullOverlay(active: Boolean, content: @Composable BoxScope.() -> Unit) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { enter.animateTo(1f, tween(140)) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = enter.value
                val s = 0.94f + 0.06f * enter.value
                scaleX = s; scaleY = s
            }
            .trapFocus(active),
        content = content,
    )
}

val Scrim = Color(0x59000000)

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
class TitleSink { var title by androidx.compose.runtime.mutableStateOf("") }
val LocalTitleSink = androidx.compose.runtime.staticCompositionLocalOf<TitleSink?> { null }

/**
 * SYS-02: Settings as a full page over the blurred Home: a centred grey title, a large glass icon in
 * the left third, and the list on the right.
 */
@Composable
fun SettingsPage(active: Boolean, icon: @Composable () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val palette = dev.glasslauncher.ui.LocalPalette.current
    val sink = remember { TitleSink() }
    FullOverlay(active) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    androidx.compose.ui.Modifier.glass(
                        dev.glasslauncher.glass.LocalBackdrop.current,
                        androidx.compose.ui.graphics.RectangleShape,
                        dev.glasslauncher.glass.GlassStyle.overlay(palette.light).copy(highlight = 0f, rim = 0f),
                    ),
                ),
        )
        androidx.tv.material3.Text(
            sink.title,
            style = dev.glasslauncher.ui.Type.title.copy(fontSize = dev.glasslauncher.ui.Type.title.fontSize * 0.62f),
            color = palette.secondary,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp),
        )
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().padding(top = 70.dp, start = 45.dp, end = 45.dp, bottom = 20.dp)) {
            Box(Modifier.weight(0.42f).fillMaxHeight(), contentAlignment = Alignment.Center) { icon() }
            Box(Modifier.weight(0.58f).fillMaxHeight()) {
                androidx.compose.runtime.CompositionLocalProvider(LocalTitleSink provides sink) {
                    Box(Modifier.fillMaxSize().trapFocus(active), content = content)
                }
            }
        }
    }
}
