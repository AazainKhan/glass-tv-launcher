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
        if (blurred.getPixel(blurred.width / 2, blurred.height / 2) ushr 24 == 0) null else blurred.asImageBitmap()
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
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 380f)) }
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
        launch { enter.animateTo(1f, tween(260)) }
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
