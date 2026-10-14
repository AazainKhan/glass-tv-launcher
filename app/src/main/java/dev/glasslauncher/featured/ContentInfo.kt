package dev.glasslauncher.featured

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.glasslauncher.ui.Type

/** The metadata calls (Cinemeta, Wikipedia) go through the shared public-only client: https, public names, bounded redirects and time. */
internal val metaHttp: okhttp3.OkHttpClient by lazy { dev.glasslauncher.net.PublicHttp.client() }

/** Cinemeta's details for [item] without the cast photos (loaded off Main once per item, cached), or null while loading or when it has none. */
@Composable
internal fun rememberExtra(item: FeaturedItem): ShelfDetails? {
    val extra by produceState<ShelfDetails?>(null, item.id) { value = runCatching { ShelfDetails.loadMeta(metaHttp, item) }.getOrNull() }
    return extra
}

/** tvOS's bordered text badge: the age rating after the info line. */
@Composable
internal fun RatingPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = Color.White.copy(alpha = 0.85f), maxLines = 1,
        modifier = modifier.border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * The info line under the hero title, the same for every app (P156): kind · genre · genre · year · runtime, then the rating pill. What
 * the row lacks comes from Cinemeta; a missing field is left out, and with nothing at all the line is not drawn.
 */
@Composable
internal fun HeroInfoLine(item: FeaturedItem, modifier: Modifier = Modifier, loadExtra: Boolean = true) {
    val extra = if (loadExtra) rememberExtra(item) else null
    val line = item.heroLine(extra)
    val pill = item.ratingPill()
    if (line == null && pill == null) {
        // Nothing structured: the source's own subtitle, as before.
        item.subtitle?.let { Text(it, style = Type.secondary, color = Color.White.copy(alpha = 0.68f), maxLines = 1, modifier = modifier) }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier.testTag("shelf-info-line")) {
        line?.let { Text(it, style = Type.secondary, color = Color.White.copy(alpha = 0.68f), maxLines = 1) }
        pill?.let { RatingPill(it) }
    }
}

/** One role row of the info panel: a lowercase header (tvOS: starring, directors, screenwriters) and its names; hidden when there are none. */
@Composable
internal fun InfoRole(header: String, names: List<String>) {
    if (names.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(header, style = Type.caption, color = Color.White.copy(alpha = 0.55f))
        Text(names.joinToString(", "), style = Type.secondary, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** "1 hr 32 min" for the minutes, null for none. */
internal fun formatRuntime(minutes: Int?): String? = minutes?.takeIf { it > 0 }?.let { m -> if (m >= 60) "${m / 60} hr" + (if (m % 60 > 0) " ${m % 60} min" else "") else "$m min" }

/**
 * The Seasons view of a TV show (P156): a season button (Select steps to the next season, wrapping), and the season's episodes as rows:
 * thumbnail, "N. Title", runtime and synopsis. Rows take focus so the list scrolls with the D-pad; a season of 20 episodes is a scroll,
 * not a page. Thumbnails decode at their shown size; nothing here blocks a frame on a load.
 */
@Composable
internal fun SeasonsView(seasons: List<Season>, modifier: Modifier = Modifier) {
    if (seasons.isEmpty()) return
    var current by remember { mutableIntStateOf(0) }
    val season = seasons[current.coerceIn(0, seasons.lastIndex)]
    val context = LocalContext.current
    Column(modifier.testTag("shelf-seasons"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (seasons.size > 1) SeasonButton("Season ${season.number}", "${current + 1} of ${seasons.size}") { current = (current + 1) % seasons.size }
        else Text("Season ${season.number}", style = Type.secondary.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(season.episodes, key = { _, e -> "${season.number}-${e.number}" }) { _, e ->
                var focused by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (focused) Color.White.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(10.dp))
                        .onFocusChanged { focused = it.isFocused }
                        // Select on an episode does nothing here (it must not close the sheet).
                        .onKeyEvent { k -> dev.glasslauncher.ui.isSelectKey(k.nativeKeyEvent.keyCode) }
                        .focusable()
                        .padding(6.dp)
                        .testTag("episode:${season.number}-${e.number}"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.12f))) {
                        e.thumbnail?.let {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(it).size(224, 126).crossfade(true).build(),
                                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${e.number}. ${e.title}", style = Type.secondary.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            formatRuntime(e.minutes)?.let { Text(it, style = Type.caption, color = Color.White.copy(alpha = 0.55f), maxLines = 1) }
                        }
                        e.overview?.let { Text(it, style = Type.caption, color = Color.White.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/** The season button: a capsule that takes focus, Select steps the season. */
@Composable
private fun SeasonButton(label: String, hint: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val pressed = remember { booleanArrayOf(false) }
    Row(
        Modifier.height(34.dp)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.2f), RoundedCornerShape(17.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val k = e.nativeKeyEvent
                val select = dev.glasslauncher.ui.isSelectKey(k.keyCode)
                if (select) {
                    if (k.action == AndroidKeyEvent.ACTION_DOWN && k.repeatCount == 0) pressed[0] = true
                    if (k.action == AndroidKeyEvent.ACTION_UP && pressed[0]) { pressed[0] = false; onClick() }
                }
                select
            }
            .focusable()
            .padding(horizontal = 16.dp)
            .testTag("season-button"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val fg = if (focused) Color.Black else Color.White
        Text(label, style = Type.label, color = fg)
        Text(hint, style = Type.caption, color = fg.copy(alpha = 0.6f))
    }
}
