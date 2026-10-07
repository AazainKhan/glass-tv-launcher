package dev.glasslauncher.featured

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import dev.glasslauncher.ui.Type
import kotlinx.coroutines.delay

/**
 * The area above the top row: a full-bleed hero image for the current item with a row of cards
 * from the user's chosen source. Rotates slowly while focus is elsewhere.
 */
@Composable
fun TopShelf(cfg: LauncherConfig, modifier: Modifier = Modifier, onFocused: (Boolean) -> Unit) {
    if (cfg.featured.source == FeaturedSourceId.Off) return
    val repo = LocalContext.current.app.featured
    LaunchedEffect(cfg.featured) { repo.refresh(cfg.featured) }
    val state by repo.state.collectAsStateWithLifecycle()
    val feed = state.feed
    var index by remember { mutableIntStateOf(0) }
    var focusInside by remember { mutableStateOf(false) }

    LaunchedEffect(feed, focusInside) {
        if (feed == null || focusInside || feed.items.size < 2) return@LaunchedEffect
        while (true) {
            delay(9_000)
            index = (index + 1) % feed.items.size
        }
    }

    val palette = LocalPalette.current
    Box(modifier.testTag("top-shelf")) {
        val item = feed?.items?.getOrNull(index)
        val context = LocalContext.current
        val request = remember(item?.image) {
            item?.image?.let {
                ImageRequest.Builder(context)
                    .data(it)
                    .size(1024, 600)
                    .transformations(HeroMask())
                    .crossfade(450)
                    .build()
            }
        }
        if (!dev.glasslauncher.DebugFlags.off(32)) AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopEnd,
            modifier = Modifier.fillMaxHeight().fillMaxWidth(0.72f).align(Alignment.TopEnd),
        )
        Column(
            Modifier
                .fillMaxHeight()
                .padding(start = 48.dp, top = 40.dp, bottom = 8.dp)
                .width(420.dp),
        ) {
            when {
                item != null -> {
                    if (item.logo != null) {
                        AsyncImage(item.logo, item.title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.height(64.dp).fillMaxWidth())
                    } else {
                        Text(item.title, style = Type.title, color = palette.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    item.subtitle?.let { Text(it, style = Type.caption, color = palette.secondary, modifier = Modifier.padding(top = 8.dp)) }
                    item.description?.let {
                        Text(it, style = Type.caption, color = palette.secondary, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                state.error != null -> Text(state.error!!, style = Type.body, color = palette.secondary, modifier = Modifier.padding(top = 30.dp))
            }
        }
        if (feed != null && feed.items.isNotEmpty() && !dev.glasslauncher.DebugFlags.off(64)) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 10.dp)) {
                Text(feed.heading.uppercase(), style = Type.label, color = palette.secondary, modifier = Modifier.padding(start = 48.dp, bottom = 6.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 62.dp)
                        .onFocusChanged { focusInside = it.hasFocus; onFocused(it.hasFocus) },
                ) {
                    itemsIndexed(feed.items, key = { _, it -> it.id }) { i, card ->
                        val context = LocalContext.current
                        if (dev.glasslauncher.DebugFlags.off(256)) Box(Modifier.width(110.dp).height(62.dp).focusable()) {
                            AsyncImage(card.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else FocusTile(
                            label = card.title,
                            onClick = { card.open(context) },
                            focusedScale = 1.1f,
                            onFocusChange = { if (it) index = i },
                            modifier = Modifier.width(110.dp).height(62.dp).testTag("featured:${card.id}"),
                        ) {
                            val cardRequest = remember(card.image) {
                                ImageRequest.Builder(context).data(card.image).size(240, 135).crossfade(false).build()
                            }
                            if (!dev.glasslauncher.DebugFlags.off(128)) AsyncImage(cardRequest, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}
