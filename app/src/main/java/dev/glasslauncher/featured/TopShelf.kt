package dev.glasslauncher.featured

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.drawWithCache
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
import coil3.network.httpHeaders
import coil3.request.transformations
import dev.glasslauncher.app
import dev.glasslauncher.home.stopAtRowEnds
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalMetrics
import dev.glasslauncher.ui.Metrics
import dev.glasslauncher.ui.Type

/**
 * The logo request, shared with the slideshow's prefetch so the next title is already in memory when
 * its slide dissolves in (fixed original size, so the memory-cache key matches).
 */
fun logoRequest(context: android.content.Context, logo: String): ImageRequest =
    // Decoded at the most it is ever drawn (a full-screen wordmark at 1080p), not at the source's size:
    // some logos are 2000+ px wide and each one sat in the memory cache at full resolution.
    ImageRequest.Builder(context).data(logo)
        .size(LOGO_MAX_W, LOGO_MAX_H).scale(coil3.size.Scale.FIT).precision(coil3.size.Precision.INEXACT)
        .transformations(LogoLegibility()).build()

private const val LOGO_MAX_W = 880
private const val LOGO_MAX_H = 300

/** Matches the backdrop's dissolve (BackdropState.swap). */
private const val SLIDE_FADE_MS = 270

/**
 * The featured item's wordmark over the full-bleed backdrop, top left like an Apple TV top shelf.
 * Logos are drawn as white wordmarks so they read on any art (some ship black-on-transparent).
 */
@Composable
fun ShelfTitle(item: FeaturedItem, expanded: () -> Float, modifier: Modifier = Modifier, textFallback: Boolean = true) {
    val m = LocalMetrics.current
    Box(modifier) {
        Column(
            Modifier
                .padding(start = m.inset, top = m.chromeInset + 14.dp)
                .widthIn(max = 340.dp)
                .graphicsLayer { alpha = (1f - expanded() * 3f).coerceIn(0f, 1f) },
        ) {
            // At rest tvOS shows only the title, top left; details wait for the full-screen view. Titles dissolve into
            // each other in step with the backdrop. The logo where the title has one, else its name in text (rows
            // from TV apps such as Nuvio's Continue Watching and Popular have no logo art, and showed only the picture;
            // P116); the home screen holds it back until the art is up, so it arrives with the art. [textFallback] is off for
            // a shelf of square covers: its name is the caption above the tray (P123), not a second, large title.
            Crossfade(item, animationSpec = tween(SLIDE_FADE_MS), label = "shelf-title") { Wordmark(it, height = 72.dp, textFallback = textFallback) }
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

/** The hero scrim's darkest point (bottom-left corner, under the title block), and its value halfway along the diagonal. */
internal const val SCRIM_PEAK = 0.62f
internal const val SCRIM_MID = 0.30f

/**
 * tvOS-style scrim (P133): the art darkens from the bottom-left, strongest under the title, metadata, synopsis and
 * buttons, fading to clear toward the top-right. Black at any theme, since the hero text is white in both. One
 * gradient rect (the brush is built once per size), no offscreen layer, and no text shadows (TYPE-03).
 */
internal fun Modifier.heroScrim(): Modifier = drawWithCache {
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(
        0f to Color.Black.copy(alpha = SCRIM_PEAK), 0.5f to Color.Black.copy(alpha = SCRIM_MID), 1f to Color.Transparent,
        start = androidx.compose.ui.geometry.Offset(0f, size.height),
        end = androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.1f),
    )
    onDrawBehind { drawRect(brush) }
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
    /** The item the title, synopsis and Cover Flow caption show: [index] once a held key has stopped repeating (P158). */
    settledIndex: Int = index,
    /** True while a D-pad key is held: the card row then reports its focus only once the key rests (P158). */
    holding: () -> Boolean = { false },
    progress: () -> Float,
    firstCard: FocusRequester,
    onIndex: (Int) -> Unit,
    onExitDown: () -> Unit,
    modifier: Modifier = Modifier,
    /** More Info is open: Home leaves Back to the sheet. */
    onSheet: (Boolean) -> Unit = {},
    /** Where Up goes from a Cover Flow (Home's status pill, P67). */
    upTo: FocusRequester? = null,
    /** The shelf app's glyph beside the Cover Flow's title (P86). */
    labelIcon: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** Which row of a sectioned shelf has focus (Home lets Down move between rows before it leaves full screen). */
    cursor: SectionCursor = remember { SectionCursor() },
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
    // A shelf of square covers (Spotify's albums and playlists) is a Cover Flow, not the wordmark, buttons and card row.
    val coverFlow = remember(feed) { CardShape.square(feed.items) }
    val onIndexNow by androidx.compose.runtime.rememberUpdatedState(onIndex)
    val holdingNow by androidx.compose.runtime.rememberUpdatedState(holding)
    val rowScope = androidx.compose.runtime.rememberCoroutineScope()
    val rowIndex = remember { SettleCommit(rowScope, { holdingNow() }) { onIndexNow(it) } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { rowIndex.cancel() } }
    Box(
        modifier.graphicsLayer {
            // Starts after the small title has faded, so the title never shows twice.
            alpha = ((progress() - 0.4f) / 0.6f).coerceIn(0f, 1f)
            translationY = (1f - progress()) * 40.dp.toPx()
        }.then(if (coverFlow) Modifier else Modifier.heroScrim()),
    ) {
        if (coverFlow) {
            CoverFlow(
                items = feed.items, index = index, settledIndex = settledIndex, onIndex = onIndex,
                onOpen = { it.open(context) }, onExit = onExitDown,
                label = feed.heading, focusRequester = firstCard, upTo = upTo,
                // The preview's covers morph into this pose and stay until they are there (CoverPreview); these take
                // over at that moment, so the covers never show twice. The title and labels fade in with the group.
                coversAlpha = { if (progress() >= HANDOVER) 1f else 0f },
                labelIcon = labelIcon,
            )
            return@Box
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(),
        ) {
            Column(Modifier.padding(start = m.inset).widthIn(max = 640.dp)) {
                Crossfade(feed.items.getOrElse(settledIndex) { item }, animationSpec = tween(SLIDE_FADE_MS), label = "shelf-details") { shown ->
                    Column {
                        Wordmark(shown, height = 76.dp)
                        HeroInfoLine(shown, Modifier.padding(top = 12.dp))
                        shown.description?.let {
                            // The whole synopsis in full screen (it was cut at two lines); five covers nearly all.
                            Text(it, style = Type.secondary, color = Color.White.copy(alpha = 0.9f), maxLines = if (feed.sections.size >= 2) 2 else 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                // tvOS: Play (Resume when started) and More Info above the row; Up from the row reaches them.
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                    ShelfButton(if (item.progress != null) "Resume" else "Play", { if (item.progress != null) item.resume(context) else item.open(context) }, Modifier.testTag("shelf-play").focusRequester(playRequester), progress = item.progress, play = true)
                    ShelfButton("More Info", { info = item }, Modifier.testTag("shelf-info").focusRequester(infoRequester).focusProperties { right = FocusRequester.Cancel }, round = true)
                }
                // No page dots: the row below is the position (the dots stopped at 12 while the cards didn't).
                Spacer(Modifier.height(18.dp))
            }
            // Several headed rows (the YouTube shelf): tvOS 27's sections, in place of the single row below.
            if (feed.sections.size >= 2) {
                SectionedRows(
                    rows = remember(feed) { ShelfSections.rows(feed) }, selectedId = item.id, firstCard = firstCard,
                    onSelect = { c -> feed.items.indexOfFirst { it.id == c.id }.takeIf { it >= 0 }?.let(rowIndex::move) },
                    onOpen = { it.open(context) },
                    onExitDown = onExitDown,
                    onUp = { runCatching { playRequester.requestFocus() }.isSuccess },
                    cursor = cursor,
                ) { card, focused ->
                    dev.glasslauncher.ui.ReliableImage(
                        card.image, Modifier.fillMaxSize(), title = card.title, subtitle = card.subtitle,
                        width = 300, height = 170, focused = focused,
                    )
                }
                return@Column
            }
            // Square cover cards when the row's art is square (Spotify's playlists and albums), 16:9 otherwise.
            val square = remember(feed) { CardShape.square(feed.items) }
            val cardWidth = if (square) 100.dp else 150.dp
            val cardHeight = if (square) 100.dp else 84.dp
            Text(feed.heading, style = Type.label, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(start = m.inset, bottom = 18.dp).testTag("shelf-heading"))
            LazyRow(
                state = listState,
                // The 34 dp under the cards belongs to the row, not to the column around it: a lazy row clips
                // 30 dp past its own bounds and a focused card's shadow reaches further, so with that space
                // outside the row the halo was cut off in a visible step. Inside it, the clip is off screen.
                contentPadding = PaddingValues(start = m.inset, end = m.inset, bottom = 34.dp),
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
                    // Every card carries its title underneath, whatever the app (user, 2026-10-09).
                    var cardFocused by remember(card.id) { androidx.compose.runtime.mutableStateOf(false) }
                    Column(Modifier.width(cardWidth)) {
                        FocusTile(
                            label = listOfNotNull(card.title, card.subtitle).joinToString(", "),
                            onClick = { card.open(context) },
                            // The cards are a fixed size, so their corners are too: not scaled with text size like Home's tiles.
                            shape = RoundedCornerShape(Metrics().tileRadius),
                            edgeLight = true,
                            onFocusChange = { cardFocused = it; if (it) rowIndex.move(i) },
                            modifier = Modifier
                                .width(cardWidth)
                                .height(cardHeight)
                                .then(if (i == index) Modifier.focusRequester(firstCard) else Modifier)
                                .testTag("featured:${card.id}"),
                        ) {
                            // Retried with backoff and again on focus; the title on a tint while it isn't there (never empty).
                            dev.glasslauncher.ui.ReliableImage(
                                card.image, Modifier.fillMaxSize(), title = card.title, subtitle = card.subtitle,
                                width = if (square) 200 else 300, height = if (square) 200 else 170, focused = cardFocused,
                            )
                        }
                        CardTitle(
                            card.title, focused = cardFocused,
                            // Clear of the focused card's grow (1.1x) and its edge light.
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("featured-title:${card.id}"),
                        )
                    }
                }
            }
        }
        info?.let { InfoSheet(it, onClose = { info = null; runCatching { infoRequester.requestFocus() } }) }
    }
}

/**
 * The title under a card: one line, ending in an ellipsis when it doesn't fit. While its card is focused a title
 * that doesn't fit scrolls to show all of it, as tvOS does: a short pause, then a steady train-scroll to the end
 * (the text loops round after a gap), twice, then resting at its start; focus leaving and coming back starts it again. A title
 * that fits never moves.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CardTitle(title: String, focused: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = dev.glasslauncher.ui.LocalUiPrefs.current.reduceMotion
    // Whether the title overflows, read from the ellipsised layout (while focused it is laid out unclipped instead).
    var truncated by remember(title) { androidx.compose.runtime.mutableStateOf(false) }
    // The full title's width (px), from the unclipped layout while it scrolls; and whether this focus's passes are done.
    var fullWidth by remember(title) { androidx.compose.runtime.mutableIntStateOf(0) }
    var passesDone by remember(title, focused) { androidx.compose.runtime.mutableStateOf(false) }
    val scrolling = focused && truncated && !reduceMotion && !passesDone
    val density = androidx.compose.ui.platform.LocalDensity.current
    // When the last pass has ended (back at its start), the ellipsis returns rather than a word cut mid-letter.
    androidx.compose.runtime.LaunchedEffect(scrolling, fullWidth) {
        if (scrolling && fullWidth > 0) {
            kotlinx.coroutines.delay(with(density) { titleMarqueeMillis(fullWidth.toFloat(), TITLE_MARQUEE_GAP.toPx(), TITLE_MARQUEE_SPEED.toPx()) })
            passesDone = true
        }
    }
    Text(
        title, style = Type.caption, color = Color.White.copy(alpha = 0.85f),
        maxLines = 1,
        // A marquee needs the text unclipped by an ellipsis: only while it scrolls (a title that fits stays as it was).
        softWrap = !scrolling,
        overflow = if (scrolling) TextOverflow.Clip else TextOverflow.Ellipsis,
        onTextLayout = { if (scrolling) fullWidth = it.size.width else if (!passesDone) truncated = it.hasVisualOverflow },
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.then(
            if (scrolling) Modifier.basicMarquee(
                iterations = TITLE_MARQUEE_PASSES,
                initialDelayMillis = TITLE_MARQUEE_DELAY_MS,
                repeatDelayMillis = TITLE_MARQUEE_DELAY_MS,
                spacing = androidx.compose.foundation.MarqueeSpacing(TITLE_MARQUEE_GAP),
                velocity = TITLE_MARQUEE_SPEED,
            ) else Modifier,
        ),
    )
}

/**
 * How many times a long title scrolls before it rests at its start. A scroll redraws Home every frame (~30% of a core
 * on the stick, measured 2026-10-09), so it must end: resting on a card keeps Home idle, as the perf budget wants.
 */
private const val TITLE_MARQUEE_PASSES = 2

/**
 * How long [TITLE_MARQUEE_PASSES] passes of a title [width] px wide take: the first pause, each pass (the title and the
 * gap after it at [velocity] px/s), the pauses between them, and a small margin so the ellipsis never lands mid-pass.
 */
internal fun titleMarqueeMillis(width: Float, gap: Float, velocity: Float): Long {
    val pass = (width + gap) / velocity * 1000f
    return (TITLE_MARQUEE_DELAY_MS * TITLE_MARQUEE_PASSES + pass * TITLE_MARQUEE_PASSES).toLong() + TITLE_MARQUEE_MARGIN_MS
}
private const val TITLE_MARQUEE_MARGIN_MS = 300L
private const val TITLE_MARQUEE_DELAY_MS = 1_000
private val TITLE_MARQUEE_GAP = 32.dp
private val TITLE_MARQUEE_SPEED = 36.dp

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
                val select = dev.glasslauncher.ui.isSelectKey(k.keyCode)
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
            ShelfIcon(dev.glasslauncher.R.drawable.ic_info_i, fg, Modifier.size(20.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                if (play) ShelfIcon(dev.glasslauncher.R.drawable.ic_play_arrow, fg, Modifier.padding(end = 4.dp).size(18.dp))
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

/**
 * More Info (P110): what the shelf does not already show: the cast with their photos, the director and writers,
 * country, seasons and awards, on a dark glass sheet over the dimmed shelf. The title, synopsis and the rating, year,
 * runtime and genre line are on the shelf, so they are not repeated beyond the title. Back closes it.
 */
@Composable
private fun InfoSheet(item: FeaturedItem, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val context = LocalContext.current
    // null while it loads; a state with nothing in it when the title has no more to say.
    val details by androidx.compose.runtime.produceState<ShelfDetails?>(null, item.id) {
        value = runCatching { ShelfDetails.load(metaHttp, item) }.getOrNull() ?: ShelfDetails()
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).testTag("shelf-info-sheet"), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(720.dp)
                .background(Color(0xE61C1C1E), RoundedCornerShape(28.dp))
                .padding(28.dp)
                .focusRequester(focus)
                .onKeyEvent { e ->
                    val k = e.nativeKeyEvent
                    // Back (and Select) close the sheet only; Home's own Back would also leave full screen.
                    val close = k.keyCode == AndroidKeyEvent.KEYCODE_BACK || dev.glasslauncher.ui.isSelectKey(k.keyCode)
                    if (close && k.action == AndroidKeyEvent.ACTION_UP) onClose()
                    close
                }
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(item.title, style = Type.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val d = details
            when {
                d == null -> Text("Loading details…", style = Type.secondary, color = Color.White.copy(alpha = 0.6f))
                d.isEmpty -> {
                    // Nothing more is known: the synopsis is the one thing the sheet can still say.
                    item.description?.let { Text(it, style = Type.body, color = Color.White.copy(alpha = 0.9f)) }
                }
                else -> {
                    HeroInfoLine(item)
                    item.description?.let { Text(it, style = Type.secondary, color = Color.White.copy(alpha = 0.9f), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    if (d.cast.isNotEmpty()) {
                        Text("starring", style = Type.caption, color = Color.White.copy(alpha = 0.55f))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            d.cast.forEach { p ->
                                Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                                        // Initials always sit under the photo: a face that never loads (offline, a missing
                                        // URL, a slow stick) leaves a person, not an empty grey disc (P110 fail).
                                        Text(initials(p.name), style = Type.title, color = Color.White.copy(alpha = 0.85f), maxLines = 1, softWrap = false)
                                        p.photo?.let {
                                            AsyncImage(
                                                model = ImageRequest.Builder(context).data(it).size(128, 128).crossfade(true)
                                                    .httpHeaders(coil3.network.NetworkHeaders.Builder().set("User-Agent", PersonPhotos.USER_AGENT).build())
                                                    .build(),
                                                contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                    Text(p.name, style = Type.caption, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                                    p.role?.let { Text(it, style = Type.caption, color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                                }
                            }
                        }
                    }
                    InfoRole("directors", d.directors)
                    InfoRole("screenwriters", d.writers)
                    InfoRow("country", d.country.orEmpty())
                    InfoRow("awards", d.awards.orEmpty())
                    SeasonsView(d.seasonList)
                }
            }
        }
    }
}

/** "Keanu Reeves" -> "KR": up to two letters, first and last word. */
internal fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> ""
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

/** One label and its value, left out when there is no value. */
@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = Type.secondary, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.width(120.dp))
        Text(value, style = Type.secondary, color = Color.White, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ShelfIcon(@androidx.annotation.DrawableRes icon: Int, tint: Color, modifier: Modifier) =
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(icon), null,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint), modifier = modifier,
    )
