package dev.glasslauncher.featured

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
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
import coil3.request.transformations
import dev.glasslauncher.home.stopAtRowEnds
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalMetrics
import dev.glasslauncher.ui.Type

/**
 * The logo request, shared with the slideshow's prefetch so the next title is already in memory when
 * its slide dissolves in (fixed original size, so the memory-cache key matches).
 */
fun logoRequest(context: android.content.Context, logo: String): ImageRequest =
    ImageRequest.Builder(context).data(logo).size(coil3.size.Size.ORIGINAL).transformations(LogoLegibility()).build()

/** Matches the backdrop's dissolve (BackdropState.swap). */
private const val SLIDE_FADE_MS = 550

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
                .widthIn(max = 340.dp)
                .graphicsLayer { alpha = (1f - expanded() * 3f).coerceIn(0f, 1f) },
        ) {
            // At rest tvOS shows only the big wordmark; details wait for the full-screen view. Titles
            // dissolve into each other in step with the backdrop.
            Crossfade(item, animationSpec = tween(SLIDE_FADE_MS), label = "shelf-title") { Wordmark(it, height = 72.dp) }
        }
    }
}

@Composable
private fun Wordmark(item: FeaturedItem, height: Dp) {
    if (item.logo != null) {
        val context = LocalContext.current
        val request = remember(item.logo) { logoRequest(context, item.logo) }
        // The baked shadow pads the bitmap by a tenth of its height on each side; draw it that much
        // larger and pull it back so the logo itself keeps its size and left edge.
        AsyncImage(
            model = request,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            modifier = Modifier.height(height * 1.2f).fillMaxWidth().offset(x = -(height * 0.1f)),
        )
    } else {
        Text(item.title, style = Type.display, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            // Starts after the small title has faded, so the title never shows twice.
            alpha = ((progress() - 0.4f) / 0.6f).coerceIn(0f, 1f)
            translationY = (1f - progress()) * 40.dp.toPx()
        },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(bottom = 34.dp),
        ) {
            Column(Modifier.padding(start = m.inset).widthIn(max = 640.dp)) {
                Crossfade(item, animationSpec = tween(SLIDE_FADE_MS), label = "shelf-details") { shown ->
                    Column {
                        Wordmark(shown, height = 76.dp)
                        shown.subtitle?.let {
                            Text(it, style = Type.secondary, color = Color.White.copy(alpha = 0.7f), maxLines = 1, modifier = Modifier.padding(top = 12.dp))
                        }
                        shown.description?.let {
                            // The whole synopsis in full screen (it was cut at two lines); five covers nearly all.
                            Text(it, style = Type.secondary, color = Color.White.copy(alpha = 0.7f), maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                // No page dots: the row below is the position (the dots stopped at 12 while the cards didn't).
                Spacer(Modifier.height(18.dp))
            }
            Text(feed.heading, style = Type.label, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(start = m.inset, bottom = 10.dp))
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = m.inset),
                horizontalArrangement = Arrangement.spacedBy(m.gutter * 0.8f),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp)
                    .stopAtRowEnds()
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
