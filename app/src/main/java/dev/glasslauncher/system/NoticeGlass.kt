package dev.glasslauncher.system

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.glasslauncher.R
import dev.glasslauncher.glass.GlassMatch
import dev.glasslauncher.home.CcSheet
import dev.glasslauncher.home.CcSheetRef
import dev.glasslauncher.home.ccSurface
import dev.glasslauncher.ui.Type

/**
 * P268: the notice card drawn in Control Center's glass material, as a Compose view. Over an app (the accessibility overlay) the glass is
 * baked from a capture of the screen behind the card's place, as Control Center's is ([sheetFor]); where there is no capture (an older
 * system, a rate-limited second capture, Home's own window) it is Control Center's tinted-glass fallback with the same lit rim and edge.
 */
internal object NoticeGlass {
    /** The screen behind the card that the sheet covers, in dp from the top-left margin: wide enough for the longest card. */
    const val REGION_W_DP = 480
    const val REGION_H_DP = 96

    /** A card's view: Compose over [ref]'s glass. [Notice] rides along as the view's tag (the tests read it). */
    fun view(context: Context, notice: Notice, ref: CcSheetRef): View = ComposeView(context).apply {
        val owner = CardOwner()
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        tag = notice
        setContent { NoticeCardContent(notice, ref) }
    }

    /**
     * Captures the screen, crops the card's region and bakes it into Control Center's sheet material; [done] gets it on the main thread, or null
     * when the capture isn't possible (API < 30, a failure, a capture within the last second). The card is not on screen yet, so it is not in the capture.
     */
    fun sheetFor(service: AccessibilityService, density: Float, done: (CcSheet?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 30) return done(null)
        var answered = false
        val once: (CcSheet?) -> Unit = { if (!answered) { answered = true; if (dev.glasslauncher.BuildConfig.DEBUG) android.util.Log.i("NoticeGlass", "sheet ${if (it != null) "baked" else "none (tinted fallback)"}"); done(it) } }
        val ok = runCatching {
            service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val shot = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    buffer.close()
                    if (shot == null) return once(null)
                    val soft = shot.copy(Bitmap.Config.ARGB_8888, false)
                    shot.recycle()
                    if (soft == null) return once(null)
                    Thread {
                        val sheet = runCatching { bake(soft, density) }.getOrNull()
                        soft.recycle()
                        android.os.Handler(android.os.Looper.getMainLooper()).post { once(sheet) }
                    }.start()
                }
                override fun onFailure(errorCode: Int) = once(null)
            })
        }.isSuccess
        if (!ok) once(null)
    }

    /** The region of [screen] behind the card, as a sheet over a window of that size. Off the main thread. */
    internal fun bake(screen: Bitmap, density: Float): CcSheet? {
        val m = (NoticeCard.MARGIN_DP * density).toInt()
        val w = (REGION_W_DP * density).toInt().coerceAtMost(screen.width - m)
        val h = (REGION_H_DP * density).toInt().coerceAtMost(screen.height - m)
        if (w <= 0 || h <= 0) return null
        val crop = Bitmap.createBitmap(screen, m, m, w, h)
        val sheet = GlassMatch.noticeSheet(crop)
        crop.recycle()
        return sheet?.let { CcSheet(it, IntSize(w, h)) }
    }
}

/** The card: glyph, title, one line, on the glass. */
@Composable
internal fun NoticeCardContent(notice: Notice, ref: CcSheetRef) {
    Row(
        Modifier.ccSurface(RoundedCornerShape(22.dp), ref).padding(start = 16.dp, end = 22.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(if (notice.iconRes != 0) notice.iconRes else R.drawable.ic_info), null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            BasicText(notice.text, style = Type.body.copy(color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 380.dp))
            notice.detail?.let { BasicText(it, style = Type.caption.copy(color = Color.White.copy(alpha = 0.72f)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 380.dp)) }
        }
    }
}

/** A card's own lifecycle and saved state, so a Compose view can live in a window the system or the service added without an activity. */
private class CardOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    init { saved.performAttach(); saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
}
