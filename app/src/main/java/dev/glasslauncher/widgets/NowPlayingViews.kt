package dev.glasslauncher.widgets

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Job
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.BoxWithConstraints
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
import dev.glasslauncher.home.ccSurface
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
import androidx.compose.ui.platform.LocalDensity
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
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Type
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester

/** Now Playing's cover has the shape of the Cover Flow albums: square, no rounding (P236). */
internal val COVER_RADIUS = 0.dp

/** The playing app's icon for the badge (P235); null when it can't be read, and for AirPlay, which has its own glyph. */
@Composable
private fun sourceIcon(pkg: String): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    return remember(pkg) {
        if (pkg == "airplay") null else runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
}

/**
 * The words after "Now Playing": nothing when the cover wears the app's badge (the badge says it), else the app's name; never a raw
 * package name (an app with no label reads as its package, so that counts as none).
 */
@Composable
private fun sourceName(pkg: String): String {
    if (pkg == "airplay" || sourceIcon(pkg) != null) return ""
    val pm = LocalContext.current.packageManager
    return remember(pkg) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("").takeIf { it != pkg && !it.contains('.') }.orEmpty() }
}

/** Ticks once a second while playing and Home is resumed (P159: not while another app is in front), so progress moves without recomposing anything else. */
@Composable
private fun rememberPosition(item: NowPlaying): Long {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val pos by produceState(item.positionNow(), item) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (item.playing) { value = item.positionNow(); delay(1_000L - value % 1_000L + 10) }
        }
        value = item.positionNow()
    }
    return pos
}

private fun clock(ms: Long) = NowPlayingTime.format(ms)

@Composable
private fun Progress(
    item: NowPlaying, color: Color, showTimes: Boolean, modifier: Modifier = Modifier, inlineTimes: Boolean = false,
    /** Where the user is seeking to, until the session reports it. */
    seeking: Long? = null,
    /** 0..1 while the scrubber has focus (read when drawing, so the animation never recomposes). */
    emphasis: () -> Float = { 0f },
) {
    val ticked = rememberPosition(item)
    val pos = seeking ?: ticked
    val f = if (item.durationMs > 0) (pos.toFloat() / item.durationMs).coerceIn(0f, 1f) else 0f
    if (inlineTimes && item.durationMs > 0) {
        // Control Center's card: elapsed, the bar, remaining on one line under the track. Never wraps: the digits are
        // tabular, one line each, and when the card is too narrow for both the remaining time goes (see NowPlayingTime.fit).
        val digits = Type.caption.copy(fontFeatureSettings = "tnum")
        val remaining = NowPlayingTime.remaining(item.durationMs, pos)
        BoxWithConstraints(modifier.fillMaxWidth().clearAndSetSemantics { testTag = "np-times" }) {
            val fontDp = digits.fontSize.value * LocalDensity.current.fontScale
            val fit = NowPlayingTime.fit(maxWidth.value, clock(pos), remaining, fontDp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(pos), style = digits, color = color.copy(alpha = 0.75f), maxLines = 1, softWrap = false)
                Canvas(Modifier.weight(1f).padding(horizontal = 6.dp).height(5.dp)) { drawBar(color, f, emphasis()) }
                if (fit == NowPlayingTime.Fit.Both) Text(remaining, style = digits, color = color.copy(alpha = 0.75f), maxLines = 1, softWrap = false)
            }
        }
        return
    }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(5.dp)) { drawBar(color, f, emphasis()) }
        // Kept out of accessibility: they change every second, which would chatter in a screen reader and
        // keeps uiautomator from ever seeing the screen idle.
        if (showTimes && item.durationMs > 0) Row(Modifier.fillMaxWidth().padding(top = 6.dp).clearAndSetSemantics { testTag = "np-times" }) {
            // Tabular digits: the times keep their width as they tick, so nothing re-lays out each second.
            val digits = Type.caption.copy(fontFeatureSettings = "tnum")
            Text(clock(pos), style = digits, color = color.copy(alpha = 0.75f), maxLines = 1, softWrap = false)
            Box(Modifier.weight(1f))
            Text(NowPlayingTime.remaining(item.durationMs, pos), style = digits, color = color.copy(alpha = 0.75f), maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The bar: 5 dp, filled to [f]. With [emphasis] > 0 (the scrubber has focus) it thickens around its centre line, to 9 dp,
 * and a knob rides the end: drawing outside the 5 dp slot, so focusing it never moves the layout.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBar(color: Color, f: Float, emphasis: Float) {
    val thick = size.height + 4.dp.toPx() * emphasis
    val top = (size.height - thick) / 2
    val r = CornerRadius(thick / 2)
    drawRoundRect(color.copy(alpha = 0.28f + 0.1f * emphasis), topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = Size(size.width, thick), cornerRadius = r)
    drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = Size(size.width * f, thick), cornerRadius = r)
    if (emphasis > 0f) drawCircle(color.copy(alpha = emphasis), radius = 7.dp.toPx() * emphasis, center = androidx.compose.ui.geometry.Offset((size.width * f).coerceIn(0f, size.width), size.height / 2))
}

private const val SEEK_STEP_MS = 10_000L

/** Test seam: the clock the seek coalescer reads (tests step it by hand; the app always uses the real one). */
object NowPlayingClock {
    @Volatile var now: () -> Long = { SystemClock.elapsedRealtime() }
}

/**
 * The progress bar as a scrubber when the session can seek (and the item has a length): focusable, Left/Right = -/+10 s
 * (held keys repeat, but at most ~5 seeks a second reach the app), Up/Down pass through to normal focus movement. Without
 * seek support it is the plain bar. Sessions that change while it is focused drop any pending seek.
 */
@Composable
private fun SeekableProgress(
    item: NowPlaying, color: Color, modifier: Modifier, inlineTimes: Boolean, requester: FocusRequester,
    onFocus: (Boolean) -> Unit = {},
) {
    val seekable = item.canSeek && item.durationMs > 0
    // [modifier] carries the focus rescue: if the scrubber goes away while focused, focus moves on, never nowhere.
    if (!seekable) { Progress(item, color, showTimes = true, modifier = modifier, inlineTimes = inlineTimes); return }
    val latest by rememberUpdatedState(item)
    val coalescer = remember(item.key) { SeekCoalescer() }
    var seeking by remember(coalescer) { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val pending = remember(coalescer) { arrayOfNulls<Job>(1) }
    DisposableEffect(coalescer) { onDispose { pending[0]?.cancel() } }
    LaunchedEffect(seeking) { if (seeking != null) { delay(1_600); seeking = null } }
    val focusT = remember { androidx.compose.animation.core.Animatable(0f) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) { focusT.animateTo(if (focused) 1f else 0f, tween(180)) }

    fun sendNow(target: Long, key: String) {
        if (latest.key != key) return
        latest.controller.seekTo(target)
        seeking = coalescer.shown(NowPlayingClock.now())
    }
    Box(
        modifier
            .focusRequester(requester)
            .onKeyEvent { ev ->
                val dir = when (ev.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> return@onKeyEvent false }
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent true
                val it = latest
                val now = NowPlayingClock.now()
                val sent = coalescer.press(it.key, it.positionNow(), it.durationMs, dir * SEEK_STEP_MS, now)
                if (sent != null) sendNow(sent, it.key)
                else coalescer.delayMs(now)?.let { wait ->
                    pending[0]?.cancel()
                    pending[0] = scope.launch {
                        delay(wait)
                        coalescer.flush(it.key, NowPlayingClock.now())?.let { t -> sendNow(t, it.key) }
                    }
                }
                true
            }
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
            .focusable()
            .testTag("np-scrubber"),
    ) {
        Progress(item, color, showTimes = true, inlineTimes = inlineTimes, seeking = seeking, emphasis = { focusT.value })
    }
}

/**
 * The cover. A new picture (the URI fetch landing, the next track) fades in over the old one in about 230 ms and never
 * snaps; the old one stays fully opaque beneath, so no frame is see-through. Until there is art, the neutral tile.
 */
@Composable
private fun Artwork(item: NowPlaying, size: Dp, radius: Dp) {
    // The playing app's icon sits inside the cover's bottom-right corner (P235), inset a little, large enough to read from the sofa.
    Box(Modifier.size(size)) {
        CoverArt(item, size, radius)
        SourceBadge(item.packageName, maxOf(size * 0.3f, 16.dp), Modifier.align(Alignment.BottomEnd).padding(size * 0.05f))
    }
}

/** The app that is playing, as a small rounded icon with a thin rim; nothing when its icon can't be read. AirPlay has its own glyph. */
@Composable
private fun SourceBadge(pkg: String, size: Dp, modifier: Modifier) {
    val icon = sourceIcon(pkg)
    val shape = RoundedCornerShape(size * 0.24f)
    if (pkg != "airplay" && icon == null) return
    Box(modifier.size(size).testTag("np-source-badge").shadow(2.dp, shape).clip(shape).background(Color(0xFF2A2C33)).border(1.dp, Color.White.copy(alpha = 0.28f), shape), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        else Image(painterResource(R.drawable.ic_airplay), null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
private fun CoverArt(item: NowPlaying, size: Dp, radius: Dp) {
    val blend = remember { ArtBlend<Bitmap>(initial = item.art) }
    var base by remember { mutableStateOf(item.art) }
    var top by remember { mutableStateOf<Bitmap?>(null) }
    var fading by remember { mutableStateOf(false) }
    var alpha by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    LaunchedEffect(item.art) {
        val t0 = androidx.compose.runtime.withFrameNanos { it / 1_000_000 }
        if (!blend.retarget(item.art, t0)) return@LaunchedEffect
        base = blend.shown; top = blend.incoming; fading = true; alpha = 0f
        while (true) {
            val t = androidx.compose.runtime.withFrameNanos { it / 1_000_000 }
            alpha = blend.alpha(t)
            if (blend.settle(t)) break
        }
        base = blend.shown; top = null; fading = false; alpha = 1f
    }
    Box(
        Modifier.size(size).shadow(radius / 2, RoundedCornerShape(radius)).clip(RoundedCornerShape(radius)).background(Color(0xFF2A2C33)),
        contentAlignment = Alignment.Center,
    ) {
        ArtLayer(base, size)
        if (fading) Box(Modifier.size(size).graphicsLayer { this.alpha = alpha }, contentAlignment = Alignment.Center) {
            // The incoming picture (or, fading back to nothing, the tile itself) over the old one.
            if (top == null) Box(Modifier.size(size).background(Color(0xFF2A2C33)), contentAlignment = Alignment.Center) { ArtLayer(null, size) } else ArtLayer(top, size)
        }
    }
}

@Composable
private fun ArtLayer(bitmap: Bitmap?, size: Dp) {
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    if (image != null) Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
    else Image(painterResource(R.drawable.ic_music_note), null, colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.6f)), modifier = Modifier.size(size * 0.4f))
}

/**
 * Home's top shelf while music plays, centred above the tray: the artwork, then the track, the artist,
 * the progress and ⏮ ⏯ ⏭. The blurred art is the backdrop (baked once per track). Text is flat (no
 * shadow, tvOS 27): white over dark art, dark over light art, secondary lines at reduced opacity.
 */
@Composable
fun NowPlayingHero(
    item: NowPlaying, onLight: Boolean, playFocus: FocusRequester, up: FocusRequester, modifier: Modifier = Modifier,
    /** Whether the scrubber holds focus (Home sends Down from it to the buttons, not to the tray). */
    onScrubFocus: (Boolean) -> Unit = {},
) {
    val fg = if (onLight) Color(0xFF0E1015) else Color.White
    val scrub = remember { FocusRequester() }
    val scrubbing = item.canSeek && item.durationMs > 0
    // Up from the buttons: the scrubber when there is one, else the status pill.
    val above = if (scrubbing) scrub else up
    val likeShown = item.like != Like.None
    val likeRescue = rememberFocusRescue(likeShown, playFocus)
    val scrubRescue = rememberFocusRescue(scrubbing, playFocus)
    LaunchedEffect(scrubbing) { if (!scrubbing) onScrubFocus(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.testTag("now-playing-hero")) {
        Artwork(item, 200.dp, COVER_RADIUS)
        Column(Modifier.padding(start = 36.dp).width(470.dp)) {
            Text(
                ("Now Playing" + sourceName(item.packageName).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()).uppercase(),
                style = Type.overline, color = fg.copy(alpha = 0.78f),
            )
            Text(item.title, style = Type.title, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            item.artist?.let {
                Text(it, style = Type.body, color = fg.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            SeekableProgress(
                item, fg, Modifier.padding(top = 16.dp).fillMaxWidth().then(scrubRescue).focusProperties { this.up = up; this.down = playFocus }, inlineTimes = false,
                requester = scrub, onFocus = onScrubFocus,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 10.dp)) {
                HeroButton(R.drawable.ic_skip_previous, "Previous Track", onLight, Modifier.focusProperties { this.up = above }) { item.previous() }
                HeroButton(
                    if (item.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow, if (item.playing) "Pause" else "Play", onLight,
                    Modifier.focusRequester(playFocus).focusProperties { this.up = above },
                ) { item.playPause() }
                HeroButton(R.drawable.ic_skip_next, "Next Track", onLight, Modifier.focusProperties { this.up = above }) { item.next() }
                // Last, so a session without a like control leaves the three above exactly where they were.
                val liked = item.like.liked
                if (liked != null) HeroButton(
                    if (liked) R.drawable.ic_favorite_fill else R.drawable.ic_favorite, if (liked) "Unlike" else "Like", onLight,
                    Modifier.then(likeRescue).focusProperties { this.up = above }, restTint = if (liked) likeAccent(onLight) else null, restDisc = liked != true,
                ) { item.toggleLike() }
            }
        }
    }
}

/** A round control on the art: a faint disc at rest, solid (white, or dark over light art) when focused. */
@Composable
private fun HeroButton(
    @DrawableRes icon: Int, label: String, onLight: Boolean, modifier: Modifier,
    /** The glyph colour at rest when it is not the plain one (a liked heart: the accent). */
    restTint: Color? = null,
    /** The faint disc at rest; a toggle that is on drops it (tvOS: on is the accent glyph alone). */
    restDisc: Boolean = true,
    onClick: () -> Unit,
) {
    val fg = if (onLight) Color(0xFF0E1015) else Color.White
    val bg = if (onLight) Color.White else Color(0xFF0E1015)
    FocusTile(label = label, onClick = onClick, shape = CircleShape, focusedScale = 1.12f, shadow = false, modifier = modifier.size(50.dp)) { focused ->
        Box(Modifier.size(50.dp).background(if (focused) fg else if (restDisc) fg.copy(alpha = 0.16f) else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), null, colorFilter = ColorFilter.tint(if (focused) bg else restTint ?: fg), modifier = Modifier.size(28.dp))
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
            .ccSurface(shape, dev.glasslauncher.home.LocalCcSheet.current).testTag("now-playing-card").padding((12 * scale).dp),
    ) {
        val playReq = remember { FocusRequester() }
        val liked = item.like.liked
        val likeRescue = rememberFocusRescue(liked != null, playReq)
        val scrubRescue = rememberFocusRescue(item.canSeek && item.durationMs > 0, playReq)
        // Art, track and the controls on one row; the progress under it, the card's full width (the times never wrap).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(item, (46 * scale).dp, COVER_RADIUS)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(item.title, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = palette.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.artist?.let { Text(it, style = Type.caption, color = palette.primary.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Row(modifier = Modifier.padding(start = 6.dp)) {
                Transport(R.drawable.ic_skip_previous, "Previous Track", scale, 34) { item.previous() }
                Transport(if (item.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow, if (item.playing) "Pause" else "Play", scale, 34, Modifier.focusRequester(playReq)) { item.playPause() }
                Transport(R.drawable.ic_skip_next, "Next Track", scale, 34) { item.next() }
                // Last: a session without a like control leaves the three above where they were.
                if (liked != null) Transport(
                    if (liked) R.drawable.ic_favorite_fill else R.drawable.ic_favorite, if (liked) "Unlike" else "Like", scale, 34,
                    likeRescue, restTint = if (liked) palette.accent else null,
                ) { item.toggleLike() }
            }
        }
        SeekableProgress(item, palette.primary, Modifier.padding(top = 4.dp).then(scrubRescue), inlineTimes = true, requester = remember { FocusRequester() })
    }
}

@Composable
private fun Transport(
    @DrawableRes icon: Int, label: String, scale: Float, size: Int = 40, modifier: Modifier = Modifier,
    /** The glyph colour at rest when it is not the plain one (a liked heart: the accent). Focus wins over it. */
    restTint: Color? = null,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    FocusTile(label = label, onClick = onClick, shape = CircleShape, focusedScale = 1.1f, shadow = false, modifier = modifier.size((size * scale).dp)) { focused ->
        Box(
            Modifier.size((size * scale).dp).background(if (focused) palette.focusFill else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(icon), null, colorFilter = ColorFilter.tint(if (focused) palette.onFocusFill else restTint ?: palette.primary), modifier = Modifier.size((size * 0.58f * scale).dp))
        }
    }
}

/** The liked heart: the system accent, deeper over light art so it keeps contrast (tvOS: an "on" toggle is its accent glyph). */
private fun likeAccent(onLight: Boolean) = if (onLight) Color(0xFF0A72B8) else Color(0xFF0091FF)

/**
 * A modifier for a control that may disappear while it holds focus: when [present] turns false having had focus, focus
 * goes to [fallback] instead of nowhere (a remote with no focus does nothing).
 */
@Composable
private fun rememberFocusRescue(present: Boolean, fallback: FocusRequester): Modifier {
    val had = remember { booleanArrayOf(false) }
    val visible by rememberUpdatedState(present)
    LaunchedEffect(present) { if (!present && had[0]) { had[0] = false; runCatching { fallback.requestFocus() } } }
    return Modifier.onFocusChanged { if (it.hasFocus) had[0] = true else if (visible) had[0] = false }
}

/** The Now Playing times: one line each, tabular digits, hours when the item is an hour or longer. */
object NowPlayingTime {
    fun format(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.ROOT, "%d:%02d", m, sec)
    }

    fun remaining(durationMs: Long, positionMs: Long): String = "-" + format(durationMs - positionMs)

    enum class Fit { Both, ElapsedOnly }

    /**
     * Whether [elapsed] and [remaining] both fit beside a bar at least [minBarDp] wide in [widthDp]; if not, the remaining
     * time is dropped (a line that never wraps). Digits are estimated at 0.62 em, a safe over-estimate for tabular figures.
     */
    fun fit(widthDp: Float, elapsed: String, remaining: String, fontDp: Float, minBarDp: Float = 36f, gapDp: Float = 12f): Fit {
        val em = fontDp * 0.62f
        val need = (elapsed.length + remaining.length) * em + gapDp + minBarDp
        return if (need <= widthDp) Fit.Both else Fit.ElapsedOnly
    }
}
