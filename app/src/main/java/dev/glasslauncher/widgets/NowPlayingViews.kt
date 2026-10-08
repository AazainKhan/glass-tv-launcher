package dev.glasslauncher.widgets

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.glasslauncher.R
import dev.glasslauncher.glass.GlassStyle
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.glass
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged

/** The playing app's name ("Spotify"), for the "Now Playing" line. */
@Composable
private fun appName(pkg: String): String {
    val pm = LocalContext.current.packageManager
    return remember(pkg) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("") }
}

/** Ticks once a second while playing, so progress moves without recomposing anything else. */
@Composable
private fun rememberPosition(item: NowPlaying): Long {
    val pos by produceState(item.positionNow(), item) {
        while (item.playing) { value = item.positionNow(); delay(1_000L - value % 1_000L + 10) }
        value = item.positionNow()
    }
    return pos
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Progress(item: NowPlaying, color: Color, showTimes: Boolean, modifier: Modifier = Modifier) {
    val pos = rememberPosition(item)
    val f = if (item.durationMs > 0) (pos.toFloat() / item.durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(5.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(color.copy(alpha = 0.28f), cornerRadius = r)
            drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
        }
        // Kept out of accessibility: they change every second, which would chatter in a screen reader and
        // keeps uiautomator from ever seeing the screen idle.
        if (showTimes && item.durationMs > 0) Row(Modifier.fillMaxWidth().padding(top = 6.dp).clearAndSetSemantics { }) {
            // Tabular digits: the times keep their width as they tick, so nothing re-lays out each second.
            val digits = Type.caption.copy(fontFeatureSettings = "tnum")
            Text(clock(pos), style = digits, color = color.copy(alpha = 0.75f))
            Box(Modifier.weight(1f))
            Text("-" + clock(item.durationMs - pos), style = digits, color = color.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun Artwork(item: NowPlaying, size: Dp, radius: Dp) {
    val art = remember(item.art) { item.art?.asImageBitmap() }
    Box(
        Modifier.size(size).shadow(radius / 2, RoundedCornerShape(radius)).clip(RoundedCornerShape(radius)).background(Color(0xFF2A2C33)),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        else Image(painterResource(R.drawable.ic_music_note), null, colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.6f)), modifier = Modifier.size(size * 0.4f))
    }
}

/**
 * Home's top shelf while music plays: the artwork (also the blurred full-bleed backdrop, baked like any
 * slide), the track large beside it, the artist, the app and the progress. Display only; Control Center
 * has the controls.
 */
@Composable
fun NowPlayingHero(item: NowPlaying, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.testTag("now-playing-hero")) {
        Artwork(item, 220.dp, 18.dp)
        Column(Modifier.padding(start = 36.dp).widthIn(max = 640.dp)) {
            Text(
                ("Now Playing" + appName(item.packageName).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()).uppercase(),
                style = Type.overline.copy(shadow = Type.strongShadow), color = Color.White.copy(alpha = 0.8f),
            )
            Text(item.title, style = Type.display.copy(shadow = Type.strongShadow), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            item.artist?.let {
                Text(it, style = Type.heading.copy(shadow = Type.strongShadow), color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
            item.album?.takeIf { it != item.title }?.let {
                Text(it, style = Type.secondary.copy(shadow = Type.strongShadow), color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Progress(item, Color.White, showTimes = true, modifier = Modifier.padding(top = 22.dp).width(420.dp))
        }
    }
}

/** Control Center's Now Playing card: artwork, title, artist, progress and ⏮ ⏯ ⏭. */
@Composable
fun NowPlayingCard(item: NowPlaying, width: Dp, scale: Float) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape((24 * scale).dp)
    // Focus on any control scrolls the whole card into view (Control Center scrolls when it's tall).
    val reveal = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(
        Modifier.width(width)
            .bringIntoViewRequester(reveal)
            .onFocusChanged { if (it.hasFocus) scope.launch { reveal.bringIntoView() } }
            .glass(LocalBackdrop.current, shape, GlassStyle.control(palette.light)).padding((12 * scale).dp).testTag("now-playing-card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(item, (52 * scale).dp, (10 * scale).dp)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(item.title, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = palette.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.artist?.let { Text(it, style = Type.caption, color = palette.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Progress(item, palette.primary, showTimes = false, modifier = Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Transport(R.drawable.ic_skip_previous, "Previous Track", scale) { item.previous() }
            Transport(if (item.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow, if (item.playing) "Pause" else "Play", scale) { item.playPause() }
            Transport(R.drawable.ic_skip_next, "Next Track", scale) { item.next() }
        }
    }
}

@Composable
private fun Transport(@DrawableRes icon: Int, label: String, scale: Float, onClick: () -> Unit) {
    val palette = LocalPalette.current
    FocusTile(label = label, onClick = onClick, shape = CircleShape, focusedScale = 1.1f, shadow = false, modifier = Modifier.size((40 * scale).dp)) { focused ->
        Box(
            Modifier.size((40 * scale).dp).background(if (focused) palette.focusFill else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(icon), null, colorFilter = ColorFilter.tint(if (focused) palette.onFocusFill else palette.primary), modifier = Modifier.size((24 * scale).dp))
        }
    }
}
