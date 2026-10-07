package dev.glasslauncher.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Whole-screen dissolve for changes that would otherwise snap: appearance, background, text size.
 * Captures what's on screen (PixelCopy, a GPU readback), applies the change underneath, then fades the
 * captured frame out. One full-screen image fade, instead of animating every colour in the tree.
 */
class ScreenDissolve(private val context: Context, private val scope: CoroutineScope) {
    var image by mutableStateOf<ImageBitmap?>(null)
        private set
    val alpha = Animatable(0f)

    /** Runs [change] behind a dissolve; falls back to running it directly if the screen can't be read. */
    fun run(durationMs: Int = 450, change: () -> Unit) {
        scope.launch {
            val frame = capture()
            if (frame == null) { change(); return@launch }
            image = frame.asImageBitmap()
            alpha.snapTo(1f)
            change()
            delay(90) // config writes land and the new frame draws underneath
            alpha.animateTo(0f, tween(durationMs, easing = FastOutSlowInEasing))
            image = null
            frame.recycle()
        }
    }

    private suspend fun capture(): Bitmap? {
        val window = context.activity()?.window ?: return null
        val view = window.decorView
        if (view.width == 0 || view.height == 0) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            runCatching {
                PixelCopy.request(window, bitmap, { result ->
                    cont.resume(if (result == PixelCopy.SUCCESS) bitmap else { bitmap.recycle(); null })
                }, Handler(Looper.getMainLooper()))
            }.onFailure { cont.resume(null) }
        }
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}

val LocalScreenDissolve = staticCompositionLocalOf<ScreenDissolve?> { null }

/** Runs [change] behind a dissolve when one is available (Home), else directly. */
fun ScreenDissolve?.dissolve(change: () -> Unit) { if (this == null) change() else run(change = change) }
