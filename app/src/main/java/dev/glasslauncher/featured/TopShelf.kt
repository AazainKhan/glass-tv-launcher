package dev.glasslauncher.featured

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalMetrics
import dev.glasslauncher.ui.Type

/**
 * The featured item's wordmark over the full-bleed backdrop, top left like an Apple TV top shelf.
 * Logos are drawn as white wordmarks so they read on any art (some ship black-on-transparent).
 */
@Composable
fun ShelfTitle(item: FeaturedItem, expanded: () -> Float, modifier: Modifier = Modifier) {
    val m = LocalMetrics.current
    Box(modifier) {
        Column(
            Modifier
                .padding(start = m.inset, top = m.chromeInset + 14.dp)
                .widthIn(max = 420.dp)
                .graphicsLayer { alpha = 1f - expanded() },
        ) {
            Wordmark(item, height = 58.dp)
            item.subtitle?.let {
                Text(it, style = Type.secondary.copy(shadow = Type.shadow), color = Color.White.copy(alpha = 0.78f), maxLines = 1, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun Wordmark(item: FeaturedItem, height: Dp) {
    if (item.logo != null) {
        AsyncImage(
            model = item.logo,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            colorFilter = ColorFilter.tint(Color.White, BlendMode.SrcIn),
            modifier = Modifier.height(height).fillMaxWidth(),
        )
    } else {
        Text(item.title, style = Type.display.copy(shadow = Type.shadow), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * "Swipe up for full screen": the tray and grid slide away, the artwork gets the whole screen, and a
 * row of the source's titles sits along the bottom. Left/right browses (the backdrop follows),
 * Select opens the title, Down or Back returns to the tray.
 */
@Composable
fun ExpandedShelf(
    feed: FeaturedFeed,
    index: Int,
    progress: () -> Float,
    firstCard: FocusRequester,
    onIndex: (Int) -> Unit,
    onExitDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = LocalMetrics.current
    val context = LocalContext.current
    val item = feed.items.getOrNull(index) ?: return
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (index - 1).coerceAtLeast(0))
    Box(
        modifier.graphicsLayer {
            alpha = progress()
            translationY = (1f - progress()) * 40.dp.toPx()
        },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(bottom = 34.dp),
        ) {
            Column(Modifier.padding(start = m.inset).widthIn(max = 520.dp)) {
                Wordmark(item, height = 76.dp)
                item.subtitle?.let {
                    Text(it, style = Type.secondary.copy(shadow = Type.shadow), color = Color.White.copy(alpha = 0.8f), maxLines = 1, modifier = Modifier.padding(top = 12.dp))
                }
                item.description?.let {
                    Text(it, style = Type.secondary.copy(shadow = Type.shadow), color = Color.White.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp, bottom = 18.dp)) {
                    val shown = feed.items.size.coerceAtMost(12)
                    repeat(shown) { i ->
                        val active = i == index.coerceAtMost(shown - 1)
                        Box(Modifier.size(if (active) 8.dp else 6.dp).background(Color.White.copy(alpha = if (active) 1f else 0.4f), CircleShape))
                    }
                }
            }
            Text(feed.heading, style = Type.label.copy(shadow = Type.shadow), color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(start = m.inset, bottom = 10.dp))
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = m.inset),
                horizontalArrangement = Arrangement.spacedBy(m.gutter * 0.8f),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp)
                    .onPreviewKeyEvent { e ->
                        val k = e.nativeKeyEvent
                        if (k.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && k.action == AndroidKeyEvent.ACTION_DOWN) { onExitDown(); true } else false
                    }
                    .testTag("featured-row"),
            ) {
                itemsIndexed(feed.items, key = { _, it -> it.id }) { i, card ->
                    val request = remember(card.image) {
                        ImageRequest.Builder(context).data(card.image).size(300, 170).crossfade(false).build()
                    }
                    FocusTile(
                        label = listOfNotNull(card.title, card.subtitle).joinToString(", "),
                        onClick = { card.open(context) },
                        shape = RoundedCornerShape(10.dp),
                        onFocusChange = { if (it) onIndex(i) },
                        modifier = Modifier
                            .width(150.dp)
                            .height(84.dp)
                            .then(if (i == index) Modifier.focusRequester(firstCard) else Modifier)
                            .testTag("featured:${card.id}"),
                    ) {
                        AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
