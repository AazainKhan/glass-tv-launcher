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
import androidx.compose.ui.semantics.testTag
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester

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
private fun Progress(item: NowPlaying, color: Color, showTimes: Boolean, modifier: Modifier = Modifier, inlineTimes: Boolean = false) {
    val pos = rememberPosition(item)
    val f = if (item.durationMs > 0) (pos.toFloat() / item.durationMs).coerceIn(0f, 1f) else 0f
    if (inlineTimes && item.durationMs > 0) {
        // Control Center's card: elapsed, the bar, remaining on one line (a line shorter, so the panel
        // fits on screen with the 13 sp type floor). Times kept out of accessibility, as below.
        val digits = Type.caption.copy(fontFeatureSettings = "tnum")
        Row(modifier.fillMaxWidth().clearAndSetSemantics { testTag = "np-times" }, verticalAlignment = Alignment.CenterVertically) {
            Text(clock(pos), style = digits, color = color.copy(alpha = 0.75f))
            Canvas(Modifier.weight(1f).padding(horizontal = 6.dp).height(5.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(color.copy(alpha = 0.28f), cornerRadius = r)
                drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
            }
            Text("-" + clock(item.durationMs - pos), style = digits, color = color.copy(alpha = 0.75f))
        }
        return
    }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(5.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(color.copy(alpha = 0.28f), cornerRadius = r)
            drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
        }
        // Kept out of accessibility: they change every second, which would chatter in a screen reader and
        // keeps uiautomator from ever seeing the screen idle.
        if (showTimes && item.durationMs > 0) Row(Modifier.fillMaxWidth().padding(top = 6.dp).clearAndSetSemantics { testTag = "np-times" }) {
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
 * Home's top shelf while music plays, centred above the tray: the artwork, then the track, the artist,
 * the progress and ⏮ ⏯ ⏭. The blurred art is the backdrop (baked once per track). Text is flat (no
 * shadow, tvOS 27): white over dark art, dark over light art, secondary lines at reduced opacity.
 */
@Composable
fun NowPlayingHero(item: NowPlaying, onLight: Boolean, playFocus: FocusRequester, up: FocusRequester, modifier: Modifier = Modifier) {
    val fg = if (onLight) Color(0xFF0E1015) else Color.White
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.testTag("now-playing-hero")) {
        Artwork(item, 200.dp, 16.dp)
        Column(Modifier.padding(start = 36.dp).width(470.dp)) {
            Text(
                ("Now Playing" + appName(item.packageName).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()).uppercase(),
                style = Type.overline, color = fg.copy(alpha = 0.78f),
            )
            Text(item.title, style = Type.title, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            item.artist?.let {
                Text(it, style = Type.body, color = fg.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            Progress(item, fg, showTimes = true, modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 10.dp)) {
                HeroButton(R.drawable.ic_skip_previous, "Previous Track", onLight, Modifier.focusProperties { this.up = up }) { item.previous() }
                HeroButton(
                    if (item.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow, if (item.playing) "Pause" else "Play", onLight,
                    Modifier.focusRequester(playFocus).focusProperties { this.up = up },
                ) { item.playPause() }
                HeroButton(R.drawable.ic_skip_next, "Next Track", onLight, Modifier.focusProperties { this.up = up }) { item.next() }
            }
        }
    }
}

/** A round control on the art: a faint disc at rest, solid (white, or dark over light art) when focused. */
@Composable
private fun HeroButton(@DrawableRes icon: Int, label: String, onLight: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fg = if (onLight) Color(0xFF0E1015) else Color.White
    val bg = if (onLight) Color.White else Color(0xFF0E1015)
    FocusTile(label = label, onClick = onClick, shape = CircleShape, focusedScale = 1.12f, shadow = false, modifier = modifier.size(50.dp)) { focused ->
        Box(Modifier.size(50.dp).background(if (focused) fg else fg.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), null, colorFilter = ColorFilter.tint(if (focused) bg else fg), modifier = Modifier.size(28.dp))
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
            .glass(LocalBackdrop.current, shape, GlassStyle.shelf(false).copy(legible = true)).testTag("now-playing-card").padding((12 * scale).dp),
    ) {
        // One row (art, track and progress, then the controls), so Control Center fits on screen with it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(item, (46 * scale).dp, (9 * scale).dp)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(item.title, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = palette.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.artist?.let { Text(it, style = Type.caption, color = palette.primary.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Progress(item, palette.primary, showTimes = true, modifier = Modifier.padding(top = 4.dp), inlineTimes = true)
            }
            Row(modifier = Modifier.padding(start = 6.dp)) {
                Transport(R.drawable.ic_skip_previous, "Previous Track", scale, 34) { item.previous() }
                Transport(if (item.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow, if (item.playing) "Pause" else "Play", scale, 34) { item.playPause() }
                Transport(R.drawable.ic_skip_next, "Next Track", scale, 34) { item.next() }
            }
        }
    }
}

@Composable
private fun Transport(@DrawableRes icon: Int, label: String, scale: Float, size: Int = 40, onClick: () -> Unit) {
    val palette = LocalPalette.current
    FocusTile(label = label, onClick = onClick, shape = CircleShape, focusedScale = 1.1f, shadow = false, modifier = Modifier.size((size * scale).dp)) { focused ->
        Box(
            Modifier.size((size * scale).dp).background(if (focused) palette.focusFill else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(icon), null, colorFilter = ColorFilter.tint(if (focused) palette.onFocusFill else palette.primary), modifier = Modifier.size((size * 0.58f * scale).dp))
        }
    }
}
