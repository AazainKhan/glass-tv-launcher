package dev.glasslauncher.widgets

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** Exists only so the launcher may read active media sessions; it ignores notifications. */
class NowPlayingService : NotificationListenerService()

data class NowPlaying(val title: String, val subtitle: String?, val packageName: String)

object NowPlayingSource {
    fun component(context: Context) = ComponentName(context, NowPlayingService::class.java)

    fun isAllowed(context: Context) =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun flow(context: Context): Flow<NowPlaying?> {
        if (!isAllowed(context)) return flowOf(null)
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return flowOf(null)
        return callbackFlow {
            val handler = Handler(Looper.getMainLooper())
            val watched = HashMap<MediaController, MediaController.Callback>()

            fun publish() {
                val playing = watched.keys.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                val meta = playing?.metadata
                trySend(
                    meta?.let {
                        val title = it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                        title?.let { t ->
                            NowPlaying(t, it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE), playing.packageName)
                        }
                    },
                )
            }

            fun watch(controllers: List<MediaController>?) {
                watched.forEach { (c, cb) -> c.unregisterCallback(cb) }
                watched.clear()
                controllers.orEmpty().forEach { c ->
                    val cb = object : MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
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
            }.onFailure { trySend(null) }
            awaitClose {
                runCatching { manager.removeOnActiveSessionsChangedListener(listener) }
                watched.forEach { (c, cb) -> c.unregisterCallback(cb) }
            }
        }.distinctUntilChanged()
    }
}

@Composable
fun NowPlayingPill() {
    val context = LocalContext.current
    val flow = remember { NowPlayingSource.flow(context) }
    val playing by flow.collectAsStateWithLifecycle(null)
    val item = playing ?: return
    val palette = LocalPalette.current
    FocusTile(
        label = "Now playing: ${item.title}",
        shape = Shapes.pill,
        focusedScale = 1.06f,
        onClick = {
            context.packageManager.getLaunchIntentForPackage(item.packageName)
                ?.let { runCatching { context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        },
        modifier = Modifier.testTag("now-playing"),
    ) { focused ->
        Text(
            listOfNotNull("♪", item.title, item.subtitle).joinToString("  "),
            style = Type.body,
            color = if (focused) palette.onFocusFill else palette.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .background(if (focused) palette.focusFill else palette.primary.copy(alpha = 0.12f), Shapes.pill)
                .padding(horizontal = 16.dp, vertical = 7.dp),
        )
    }
}
