package dev.glasslauncher.home

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size as androidxSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.glasslauncher.R
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Add-on tile STATES with fake data (P83 M6, spec 6): proves the visuals before the add-on platform exists. Only in a
 * debuggable build and only when `files/cc-showcase` exists (`adb shell run-as dev.glasslauncher touch files/cc-showcase`).
 * Add-ons send `{glyphKey, label, value, state, actions}`; Glass draws it, so these are drawn with Glass's own pieces.
 */
internal object CcShowcase {
    /** Set by Control Center when it opens: the catalog offers these only while on. */
    @Volatile var enabled = false

    fun flag(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && File(context.filesDir, "cc-showcase").exists()

    data class Fake(val id: String, val name: String, val description: String, @DrawableRes val icon: Int, val addon: String)

    val tiles = listOf(
        Fake("addon:ha/scene#movie_night", "Movies", "Run the Movie Night scene (Home Assistant)", R.drawable.ic_home, "Home Assistant"),
        Fake("addon:claude/agents", "Agents", "Claude Code status and approvals", R.drawable.ic_code, "Claude Code Bridge"),
        Fake("addon:plex/continue", "Plex", "Resume what you were watching", R.drawable.ic_play_arrow, "Plex"),
        Fake("addon:weather/now", "Weather", "Current conditions", R.drawable.ic_weather_clear_day, "Weather"),
    )

    fun isShowcase(id: String) = tiles.any { it.id == id }

    /** The pending permission request the Agents card shows (fake). */
    const val REQUEST_TITLE = "Allow Bash?"
    const val REQUEST_WHO = "glass-launcher · Yang"
    const val REQUEST_COMMAND = "git push origin sdd/cc-custom --force-with-lease"
    const val REQUEST_WHERE = "in ~/Documents/glass-sdd-preview"

    val Green = Color(0xFF30D158)
    val Amber = Color(0xFFFF9F0A)
}

/** State the Agents tile and its card share (kept by Control Center so both see it). */
internal class CcAgentState {
    var waiting by mutableStateOf(1)
    var result by mutableStateOf<String?>(null)
}

/**
 * One showcase tile at [size]. Select does what the add-on's action would (a scene turns on for 3 s; Agents opens the
 * approve card through [openAgents]); the rest are state only.
 */
@Composable
internal fun ShowcaseTile(
    id: String, size: CcSize, w: Dp, h: Dp, agents: CcAgentState, openAgents: () -> Unit,
    modifier: Modifier, onLongClick: (() -> Unit)?, wiggle: Boolean, tap: (() -> Unit) -> () -> Unit, playOr: (() -> Unit) -> () -> Unit,
    onLabel: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val one = size == CcSize.S1x1
    when (id) {
        "addon:ha/scene#movie_night" -> {
            // 0 off, 1 loading ("…", a static dot: no shimmer), 2 on for 3 s (scenes are momentary).
            var state by remember { mutableStateOf(0) }
            val value = when (state) { 1 -> "…"; 2 -> "On"; else -> "Off" }
            val go = tap {
                if (state == 0) scope.launch { state = 1; delay(700); state = 2; delay(3_000); state = 0 }
            }
            Tile("Movies", value, R.drawable.ic_home, one, w, h, on = state == 2, accent = CcShowcase.Green, modifier, onLongClick, wiggle, go, playOr { go() }, onLabel)
        }
        "addon:claude/agents" -> {
            val value = agents.result ?: if (agents.waiting > 0) "${agents.waiting} waiting" else "3 working"
            val go = tap { if (agents.waiting > 0) openAgents() }
            // Amber while a request waits (Glass draws the disc; the add-on only names the state).
            Tile("Agents", value, R.drawable.ic_code, one, w, h, on = agents.waiting > 0, accent = CcShowcase.Amber, modifier, onLongClick, wiggle, go, playOr { go() }, onLabel)
        }
        "addon:plex/continue" -> {
            val go = tap { }
            Tile("Plex", "Bear 23m", R.drawable.ic_play_arrow, one, w, h, on = false, accent = Color.White, modifier, onLongClick, wiggle, go, playOr { go() }, onLabel)
        }
        "addon:weather/now" -> {
            val go = tap { }
            if (one) CcTile("Weather, 18 degrees", null, androidx.compose.foundation.shape.CircleShape, w, h, onClick = go, modifier = modifier, onLongClick = onLongClick, onPlay = playOr { go() }, wiggle = wiggle) { fg ->
                // A short value (3 characters at most) may replace the glyph at 1x1.
                BasicText("18°", style = Type.heading.copy(color = fg))
            } else Tile("18° Clear", "H 22  L 11", R.drawable.ic_weather_clear_day, false, w, h, on = false, accent = Color.White, modifier, onLongClick, wiggle, go, playOr { go() }, onLabel)
        }
    }
}

@Composable
private fun Tile(
    label: String, value: String, @DrawableRes icon: Int, one: Boolean, w: Dp, h: Dp, on: Boolean, accent: Color,
    modifier: Modifier, onLongClick: (() -> Unit)?, wiggle: Boolean, onClick: () -> Unit, onPlay: () -> Unit, onLabel: (String?) -> Unit,
) {
    if (one) Round(icon, "$label, $value", on = on, onLabel = onLabel, modifier = modifier, onLongClick = onLongClick, onPlay = onPlay, wiggle = wiggle, onClick = onClick)
    else CcTile(label, value, Shapes.pill, w, h, onClick = onClick, modifier = modifier, onLongClick = onLongClick, onPlay = onPlay, wiggle = wiggle, on = on) { fg ->
        PillContent(icon, label, value, fg, on = on, accent = accent)
    }
}

/**
 * The permission card (spec 6.3): Glass draws it, the add-on only supplies the request. Deny has focus first and Back
 * is Deny; there is no "Always allow"; an unanswered request falls through to the Mac's own prompt.
 */
@Composable
internal fun CcAgentCard(sz: CcSizes, decide: (allowed: Boolean) -> Unit) {
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(sz.gap), modifier = Modifier.width(sz.column).testTag("cc-agent-card")) {
        BasicText(CcShowcase.REQUEST_TITLE, style = Type.heading.copy(color = Color.White))
        BasicText(CcShowcase.REQUEST_WHO, style = Type.caption.copy(color = Color.White.copy(alpha = 0.75f)))
        Box(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(10.dp),
        ) {
            BasicText(
                CcShowcase.REQUEST_COMMAND, style = Type.caption.copy(color = Color.White, fontFamily = FontFamily.Monospace),
                maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        BasicText(CcShowcase.REQUEST_WHERE, style = Type.caption.copy(color = Color.White.copy(alpha = 0.75f)))
        // Deny first, in the focused (white) capsule; Allow once needs a deliberate Down and Select.
        CcTile("Deny", null, Shapes.pill, sz.column, sz.pill, onClick = { decide(false) }, modifier = Modifier.focusRequester(first).testTag("cc-agent-deny")) { fg ->
            BasicText("Deny", style = Type.body.copy(color = fg))
        }
        CcTile("Allow once", null, Shapes.pill, sz.column, sz.pill, onClick = { decide(true) }) { fg ->
            BasicText("Allow once", style = Type.body.copy(color = fg))
        }
        BasicText("Expires in 9:42 · then asks on Mac", style = Type.caption.copy(color = Color.White.copy(alpha = 0.7f)))
    }
}

/**
 * Now Playing (spec 6.1): built in, because Glass already holds the media sessions. 2x2: the cover (decoded at shown
 * size by the session), title, artist and a 2 dp progress line; 2x1: cover, title, artist; 1x1: play or pause.
 * Select = play/pause. Idle: "Not playing".
 */
@Composable
internal fun NowPlayingTile(
    np: dev.glasslauncher.widgets.NowPlaying?, size: CcSize, w: Dp, h: Dp, modifier: Modifier, onLongClick: (() -> Unit)?, wiggle: Boolean,
    tap: (() -> Unit) -> () -> Unit, playOr: (() -> Unit) -> () -> Unit, onLabel: (String?) -> Unit,
) {
    val go = tap { np?.playPause() }
    val label = np?.title ?: "Not playing"
    when (size) {
        CcSize.S1x1 -> Round(if (np?.playing == true) R.drawable.ic_pause else R.drawable.ic_play_arrow, "Now Playing, $label", on = np?.playing == true, onLabel = onLabel,
            modifier = modifier, onLongClick = onLongClick, onPlay = playOr { go() }, wiggle = wiggle, onClick = go)
        CcSize.S2x2 -> CcTile("Now Playing", label, androidx.compose.foundation.shape.RoundedCornerShape(26.dp), w, h, onClick = go, modifier = modifier,
            onLongClick = onLongClick, onPlay = playOr { go() }, wiggle = wiggle) { fg ->
            var now by remember { mutableStateOf(0L) }
            if (np != null && np.playing) androidx.compose.runtime.LaunchedEffect(np.key) { while (true) { now = np.positionNow(); delay(1_000) } }
            Column(Modifier.padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                val art = np?.art
                if (art != null) androidx.compose.foundation.Image(
                    art.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.androidxSize(70.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                ) else androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(R.drawable.ic_music_note), null,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(fg), modifier = Modifier.androidxSize(36.dp),
                )
                BasicText(label, style = Type.caption.copy(color = fg, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                BasicText(np?.artist ?: "", style = Type.caption.copy(color = fg.copy(alpha = 0.7f)), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                val frac = if (np != null && np.durationMs > 0) (np.positionNow() / np.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(2.dp).background(fg.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(frac).height(2.dp).background(fg))
                }
            }
        }
        else -> CcTile("Now Playing", label, Shapes.pill, w, h, onClick = go, modifier = modifier, onLongClick = onLongClick, onPlay = playOr { go() }, wiggle = wiggle,
            on = np?.playing == true) { fg ->
            PillContent(R.drawable.ic_music_note, if (np != null) np.title else "Music", if (np != null) np.artist else "Idle", fg, on = np?.playing == true, accent = Color.White)
        }
    }
}
