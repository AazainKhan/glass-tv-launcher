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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.focusable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
            // Only a real logo shows at rest: a plain-text title would be one generic font for every
            // service. The name appears in full screen (Up), with its details.
            Crossfade(item, animationSpec = tween(SLIDE_FADE_MS), label = "shelf-title") { if (it.logo != null) Wordmark(it, height = 72.dp, textFallback = false) }
        }
    }
}

@Composable
private fun Wordmark(item: FeaturedItem, height: Dp, textFallback: Boolean = true) {
    // A logo that can't load (a few titles have none) falls back to the name where text is allowed.
    var failed by androidx.compose.runtime.remember(item.logo) { androidx.compose.runtime.mutableStateOf(false) }
    if (item.logo != null && !failed) {
        val context = LocalContext.current
        val request = remember(item.logo) { logoRequest(context, item.logo) }
        // The baked shadow pads the bitmap by a tenth of its height on each side; draw it that much
        // larger and pull it back so the logo itself keeps its size and left edge.
        AsyncImage(
            model = request,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { failed = true },
            modifier = Modifier.height(height * 1.2f).fillMaxWidth().offset(x = -(height * 0.1f)).testTag("shelf-logo"),
        )
    } else if (textFallback) {
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
    /** More Info is open: Home leaves Back to the sheet. */
    onSheet: (Boolean) -> Unit = {},
) {
    val m = LocalMetrics.current
    val context = LocalContext.current
    val item = feed.items.getOrNull(index) ?: return
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (index - 1).coerceAtLeast(0))
    var info by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<FeaturedItem?>(null) }
    val infoRequester = remember { FocusRequester() }
    val playRequester = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(info != null) { onSheet(info != null) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onSheet(false) } }
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
                        shown.metaLine()?.let {
                            Text(it, style = Type.secondary, color = Color.White.copy(alpha = 0.68f), maxLines = 1, modifier = Modifier.padding(top = 12.dp))
                        }
                        shown.description?.let {
                            // The whole synopsis in full screen (it was cut at two lines); five covers nearly all.
                            Text(it, style = Type.secondary, color = Color.White.copy(alpha = 0.9f), maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                // tvOS: Play (Resume when started) and More Info above the row; Up from the row reaches them.
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                    ShelfButton(if (item.progress != null) "Resume" else "Play", { item.open(context) }, Modifier.testTag("shelf-play").focusRequester(playRequester), progress = item.progress, play = true)
                    ShelfButton("More Info", { info = item }, Modifier.testTag("shelf-info").focusRequester(infoRequester).focusProperties { right = FocusRequester.Cancel }, round = true)
                }
                // No page dots: the row below is the position (the dots stopped at 12 while the cards didn't).
                Spacer(Modifier.height(18.dp))
            }
            Text(feed.heading, style = Type.label, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(start = m.inset, bottom = 18.dp).testTag("shelf-heading"))
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
                        when {
                            k.action != AndroidKeyEvent.ACTION_DOWN -> false
                            k.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { onExitDown(); true }
                            // Up from the row: Play first (spatial search could land on More Info).
                            k.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP -> runCatching { playRequester.requestFocus() }.isSuccess
                            else -> false
                        }
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
        info?.let { InfoSheet(it, onClose = { info = null; runCatching { infoRequester.requestFocus() } }) }
    }
}

/** A tvOS hero button: a capsule (Play, with a progress bar for Resume) or a round icon (More Info). */
@Composable
private fun ShelfButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    play: Boolean = false,
    round: Boolean = false,
) {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val pressed = remember { booleanArrayOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.08f else 1f, dev.glasslauncher.ui.Motion.focusIn(), label = "shelfButton")
    val fg = if (focused) Color.Black else Color.White
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // Slim, as on tvOS's hero: 36 dp capsules.
            .then(if (round) Modifier.size(36.dp) else Modifier.height(36.dp).widthIn(min = 120.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.2f), if (round) CircleShape else RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val k = e.nativeKeyEvent
                val select = k.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || k.keyCode == AndroidKeyEvent.KEYCODE_ENTER
                if (select) {
                    if (k.action == AndroidKeyEvent.ACTION_DOWN && k.repeatCount == 0) pressed[0] = true
                    if (k.action == AndroidKeyEvent.ACTION_UP && pressed[0]) { pressed[0] = false; onClick() }
                }
                select
            }
            .semantics { contentDescription = label; role = androidx.compose.ui.semantics.Role.Button }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        if (round) {
            Text("i", style = Type.label, color = fg)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                if (play) Text("▶", style = Type.label, color = fg, modifier = Modifier.padding(end = 6.dp))
                Column {
                    Text(label, style = Type.label, color = fg)
                    if (progress != null) Box(Modifier.padding(top = 3.dp).width(64.dp).height(3.dp).background(fg.copy(alpha = 0.3f), CircleShape)) {
                        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = progress.coerceIn(0f, 1f); transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }.background(fg, CircleShape))
                    }
                }
            }
        }
    }
}

/** More Info: the whole synopsis and details on a dark glass sheet over the dimmed shelf. Back closes it. */
@Composable
private fun InfoSheet(item: FeaturedItem, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).testTag("shelf-info-sheet"), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(560.dp)
                .background(Color(0xE61C1C1E), RoundedCornerShape(28.dp))
                .padding(32.dp)
                .focusRequester(focus)
                .onKeyEvent { e ->
                    val k = e.nativeKeyEvent
                    // Back (and Select) close the sheet only; Home's own Back would also leave full screen.
                    val close = k.keyCode == AndroidKeyEvent.KEYCODE_BACK || k.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER
                    if (close && k.action == AndroidKeyEvent.ACTION_UP) onClose()
                    close
                }
                .focusable(),
        ) {
            Text(item.title, style = Type.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.metaLine()?.let { Text(it, style = Type.secondary, color = Color.White.copy(alpha = 0.72f), modifier = Modifier.padding(top = 8.dp)) }
            item.description?.let { Text(it, style = Type.body, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(top = 16.dp)) }
        }
    }
}
