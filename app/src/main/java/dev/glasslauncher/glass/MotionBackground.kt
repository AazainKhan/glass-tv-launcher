package dev.glasslauncher.glass

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.glasslauncher.app
import dev.glasslauncher.data.ScreensaverConfig
import dev.glasslauncher.dream.AerialCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * "Motion" background: a muted Aerial video behind Home, like tvOS's moving top shelf. The video is a
 * SurfaceView the hardware composer overlays, so it costs the GPU almost nothing. Glass can't blur a
 * moving image live on this hardware, so a tiny frame is sampled every few seconds and baked into the
 * backdrop the glass and scroll blur use (android-fire-tv.md, "video behind glass").
 */
@OptIn(UnstableApi::class)
@Composable
fun MotionBackground(cfg: ScreensaverConfig, state: BackdropState, light: Boolean, paused: Boolean) {
    val context = LocalContext.current
    val app = context.app
    val surface = remember { SurfaceView(context) }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
            setVideoSurfaceView(surface)
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(Unit) {
        val videos = AerialCatalog(context, app.http).videos().shuffled()
        if (videos.isEmpty()) return@LaunchedEffect
        player.setMediaItems(videos.map { MediaItem.fromUri(it.url(cfg.quality)) })
        player.prepare()
        player.play()
    }
    LaunchedEffect(paused) { if (paused) player.pause() else player.play() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(if (state.backdrop == null) 1_500 else 6_000)
            if (!player.isPlaying || surface.width == 0) continue
            val frame = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
            val ok = suspendCancellableCoroutine { cont ->
                runCatching {
                    PixelCopy.request(surface, frame, { result -> cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
                }.onFailure { cont.resume(false) }
            }
            if (ok) state.swap(app.wallpapers.fromImage(frame, WallpaperLoader.Scene.Hero, background = true, light = light), animate = false)
            frame.recycle()
        }
    }
    AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
}
