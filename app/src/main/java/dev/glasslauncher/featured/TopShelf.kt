package dev.glasslauncher.featured

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import dev.glasslauncher.app
import dev.glasslauncher.data.FeaturedSourceId
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.LocalUiPrefs
import dev.glasslauncher.ui.Safe
import dev.glasslauncher.ui.Type
import kotlinx.coroutines.delay

/**
 * The area above the top row, in the spirit of tvOS's inset-banner Top Shelf: a large hero for the
 * current item, short metadata, page dots, and a row of cards from the user's chosen source.
 * Rotates slowly while focus is elsewhere (never with reduce motion).
 */
@Composable
fun TopShelf(cfg: LauncherConfig, modifier: Modifier = Modifier, paused: Boolean = false, onFocused: (Boolean) -> Unit) {
    if (cfg.featured.source == FeaturedSourceId.Off) return
    val context = LocalContext.current
    val repo = context.app.featured
    val prefs = LocalUiPrefs.current
    LaunchedEffect(cfg.featured) { repo.refresh(cfg.featured) }
    val state by repo.state.collectAsStateWithLifecycle()
    val feed = state.feed
    var index by remember { mutableIntStateOf(0) }
    var focusInside by remember { mutableStateOf(false) }

    LaunchedEffect(feed, focusInside, prefs.reduceMotion, paused) {
        if (feed == null || focusInside || feed.items.size < 2 || prefs.reduceMotion || paused) return@LaunchedEffect
        while (true) {
            delay(9_000)
            index = (index + 1) % feed.items.size
        }
    }

    val palette = LocalPalette.current
    Box(modifier.testTag("top-shelf")) {
        val item = feed?.items?.getOrNull(index)
        val request = remember(item?.image, prefs.reduceMotion) {
            item?.image?.let {
                ImageRequest.Builder(context)
                    .data(it)
                    .size(1024, 600)
                    .transformations(HeroMask())
                    .crossfade(if (prefs.reduceMotion) 0 else 450)
                    .build()
            }
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopEnd,
            modifier = Modifier.fillMaxHeight().fillMaxWidth(0.72f).align(Alignment.TopEnd),
        )
        Column(
            Modifier
                .fillMaxHeight()
                .padding(start = Safe.horizontal, top = Safe.top + 8.dp, bottom = 8.dp)
                .width(440.dp),
        ) {
            when {
                item != null -> {
                    if (item.logo != null) {
                        AsyncImage(
                            item.logo, item.title,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            modifier = Modifier.height(68.dp).fillMaxWidth(0.85f),
                        )
                    } else {
                        Text(item.title, style = Type.display, color = palette.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    item.subtitle?.let {
                        Text(it, style = Type.secondary, color = palette.secondary, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
                    }
                    item.description?.let {
                        Text(it, style = Type.secondary, color = palette.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (feed.items.size > 1) PageDots(count = feed.items.size.coerceAtMost(12), active = index.coerceAtMost(11))
                }
                state.error != null -> Text(state.error!!, style = Type.body, color = palette.secondary, modifier = Modifier.padding(top = 40.dp))
            }
        }
        if (feed != null && feed.items.isNotEmpty()) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 12.dp)) {
                Text(feed.heading.uppercase(), style = Type.overline, color = palette.secondary, modifier = Modifier.padding(start = Safe.horizontal, bottom = 8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Safe.horizontal),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 66.dp)
                        .onFocusChanged { focusInside = it.hasFocus; onFocused(it.hasFocus) },
                ) {
                    itemsIndexed(feed.items, key = { _, it -> it.id }) { i, card ->
                        val cardRequest = remember(card.image) {
                            ImageRequest.Builder(context).data(card.image).size(240, 135).crossfade(false).build()
                        }
                        FocusTile(
                            label = listOfNotNull(card.title, card.subtitle).joinToString(", "),
                            onClick = { card.open(context) },
                            onFocusChange = { if (it) index = i },
                            modifier = Modifier.width(118.dp).height(66.dp).testTag("featured:${card.id}"),
                        ) {
                            AsyncImage(cardRequest, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, active: Int) {
    val palette = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(if (i == active) 8.dp else 6.dp)
                    .background(if (i == active) palette.primary else palette.faint, CircleShape),
            )
        }
    }
}
