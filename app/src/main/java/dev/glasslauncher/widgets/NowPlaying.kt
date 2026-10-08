package dev.glasslauncher.widgets

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Exists only so the launcher may read active media sessions; it ignores notifications. */
class NowPlayingService : NotificationListenerService()

/**
 * The media session in front: what's playing (or paused) in any app that publishes one (Spotify, Amazon
 * Music, YouTube Music, VLC…). Position is extrapolated from the last update, so progress needs no polling.
 */
data class NowPlaying(
    val title: String,
    val artist: String?,
    val album: String?,
    val packageName: String,
    val art: Bitmap?,
    val durationMs: Long,
    val positionMs: Long,
    val positionAt: Long,
    val speed: Float,
    val playing: Boolean,
    val controller: MediaController,
) {
    fun positionNow(now: Long = SystemClock.elapsedRealtime()): Long =
        if (!playing) positionMs else (positionMs + ((now - positionAt) * speed).toLong()).coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)

    fun playPause() = if (playing) controller.transportControls.pause() else controller.transportControls.play()
    fun next() = controller.transportControls.skipToNext()
    fun previous() = controller.transportControls.skipToPrevious()

    /** Identity of the track and its state, for animations and backdrop keys (not the per-update position). */
    val key: String get() = "$packageName|$title|$artist|${art?.generationId}"
}

object NowPlayingSource {
    fun component(context: Context) = ComponentName(context, NowPlayingService::class.java)

    fun isAllowed(context: Context) =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun flow(context: Context): Flow<NowPlaying?> {
        if (!isAllowed(context)) { android.util.Log.w("GlassNowPlaying", "notification access not granted"); return flowOf(null) }
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return flowOf(null)
        return callbackFlow {
            val handler = Handler(Looper.getMainLooper())
            val watched = HashMap<MediaController, MediaController.Callback>()
            var artFor: MediaMetadata? = null
            var art: Bitmap? = null

            fun publish() {
                // Playing first; else a paused session (so it can be resumed from Control Center).
                val c = watched.keys.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: watched.keys.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
                val meta = c?.metadata
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                if (c == null || meta == null || title == null) { trySend(null); return }
                if (meta !== artFor) {
                    artFor = meta
                    val raw = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                    art = raw?.let { scaled(it) }
                }
                val state = c.playbackState
                trySend(
                    NowPlaying(
                        title = title,
                        artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                        album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM),
                        packageName = c.packageName,
                        art = art,
                        durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
                        positionMs = state?.position ?: 0,
                        positionAt = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
                        speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                        playing = state?.state == PlaybackState.STATE_PLAYING,
                        controller = c,
                    ),
                )
            }

            fun watch(controllers: List<MediaController>?) {
                watched.forEach { (c, cb) -> c.unregisterCallback(cb) }
                watched.clear()
                controllers.orEmpty().forEach { c ->
                    val cb = object : MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                        // A killed app's controller keeps reporting its last state (playing), so it goes.
                        override fun onSessionDestroyed() { c.unregisterCallback(this); watched.remove(c); publish() }
                    }
                    c.registerCallback(cb, handler)
                    watched[c] = cb
                }
                publish()
            }

            val listener = MediaSessionManager.OnActiveSessionsChangedListener { watch(it) }
            runCatching {
                manager.addOnActiveSessionsChangedListener(listener, component(context), handler)
                watch(manager.getActiveSessions(component(context)))
            }.onFailure { android.util.Log.w("GlassNowPlaying", "can't read media sessions", it); trySend(null) }
            awaitClose {
                runCatching { manager.removeOnActiveSessionsChangedListener(listener) }
                watched.forEach { (c, cb) -> c.unregisterCallback(cb) }
            }
        }.distinctUntilChanged { a, b ->
            a?.key == b?.key && a?.playing == b?.playing && a?.positionMs == b?.positionMs && a?.durationMs == b?.durationMs
        }
    }

    /** Artwork at most 512 px: enough for the card and the baked backdrop, small to keep. */
    private fun scaled(b: Bitmap): Bitmap {
        val max = 512
        if (b.width <= max && b.height <= max) return b.copy(Bitmap.Config.ARGB_8888, false) ?: b
        val k = max / maxOf(b.width, b.height).toFloat()
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }
}

/**
 * The art as Home's takeover backdrop: blurred far past recognition, like tvOS (shrunk to 10 px, then
 * grown back in smooth steps; one cheap pass per track), and dimmed a little so white text reads on any cover.
 */
fun backdropArt(art: Bitmap): Bitmap {
    var b = Bitmap.createScaledBitmap(art, 10, 10, true)
    for (size in intArrayOf(20, 40, 80, 160, 320)) {
        val next = Bitmap.createScaledBitmap(b, size, size * 9 / 16, true)
        if (b !== art) b.recycle()
        b = next
    }
    val out = b.copy(Bitmap.Config.ARGB_8888, true)
    b.recycle()
    android.graphics.Canvas(out).drawColor(android.graphics.Color.argb(70, 0, 0, 0))
    return out
}

/** One shared session watcher for Home and Control Center (started while anything shows it). */
class NowPlayingRepository(context: Context, scope: CoroutineScope) {
    val state: StateFlow<NowPlaying?> = NowPlayingSource.flow(context.applicationContext)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)
}
