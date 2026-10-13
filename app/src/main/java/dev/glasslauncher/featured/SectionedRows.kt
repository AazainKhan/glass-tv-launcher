package dev.glasslauncher.featured

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.glasslauncher.home.stopAtRowEnds
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalMetrics
import dev.glasslauncher.ui.Metrics
import dev.glasslauncher.ui.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A section row's height at rest: heading, 16:9 card, its title, and the gap to the next row. */
private val ROW_HEIGHT = 176.dp

/**
 * tvOS 27's sectioned Top Shelf: headed rows of 16:9 cards stacked under the title, one row high with the next row's heading peeking, scrolled so the row
 * with focus is always in view. Left and Right move along a row (stopping at its ends); Up and Down move between rows and
 * land on the card the viewer left in that row ([SectionMemory]), Down from the last row and Up from the first hand back
 * to the shelf ([onExitDown], [onUp]). [selectedId] is the title the backdrop shows; focus on a card reports it by [onSelect].
 * The rows are plain lazy lists: nothing here recomposes while focus rests.
 */
@Composable
internal fun SectionedRows(
    rows: List<FeaturedSection>,
    selectedId: String?,
    firstCard: FocusRequester,
    onSelect: (FeaturedItem) -> Unit,
    onOpen: (FeaturedItem) -> Unit,
    onExitDown: () -> Unit,
    onUp: () -> Boolean,
    cursor: SectionCursor = remember { SectionCursor() },
    modifier: Modifier = Modifier,
    art: @Composable (card: FeaturedItem, focused: Boolean) -> Unit,
) {
    val m = LocalMetrics.current
    val scope = rememberCoroutineScope()
    val memory = remember(rows) { SectionMemory() }
    // The row with focus (or the one the backdrop's title is in until focus lands), and where focus is in it.
    var active by remember(rows) { mutableIntStateOf(ShelfSections.rowOf(rows, selectedId)) }
    val at = remember(rows) { intArrayOf(active, 0) }
    cursor.rows = rows.size
    androidx.compose.runtime.DisposableEffect(cursor) { onDispose { cursor.rows = 1; cursor.row = 0; cursor.focusedCard = -1L; cursor.enterRows = null } }
    val colState = rememberLazyListState(initialFirstVisibleItemIndex = active)
    val rowStates = remember(rows) {
        rows.mapIndexed { i, r ->
            val start = if (i == active) r.items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0) else 0
            LazyListState((start - 1).coerceAtLeast(0))
        }
    }
    val requesters = remember(rows) { HashMap<Long, FocusRequester>() }
    fun requester(s: Int, i: Int) = requesters.getOrPut(s * 1000L + i) { FocusRequester() }
    // The rows glide to keep the focused one in view (one animated scroll, started on a change, not per frame).
    LaunchedEffect(active) { colState.animateScrollToItem(active) }

    fun go(to: Int) {
        val size = rows[to].items.size
        val pos = memory.landing(to, size, at[1])
        scope.launch {
            val st = rowStates[to]
            if (st.layoutInfo.visibleItemsInfo.none { it.index == pos }) st.scrollToItem((pos - 1).coerceAtLeast(0))
            // The card may not be composed until the next frame or two.
            repeat(4) {
                if (runCatching { requester(to, pos).requestFocus() }.isSuccess) return@launch
                delay(16)
            }
        }
    }

    // Down from the hero's Play and More Info lands in the first row, on the card the viewer left there (P132).
    cursor.enterRows = { go(0); true }

    LazyColumn(
        state = colState,
        userScrollEnabled = false,
        // Room under the last row so it can scroll up to the top like the others (no previous row's titles left hanging above it).
        contentPadding = PaddingValues(bottom = 60.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = ROW_HEIGHT + 44.dp)
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                val dir = when (k.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 1
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> -1
                    else -> return@onPreviewKeyEvent false
                }
                if (k.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                val to = ShelfSections.neighbour(at[0], dir, rows.size)
                when {
                    to != null -> { go(to); true }
                    dir > 0 -> { onExitDown(); true }
                    else -> onUp()
                }
            }
            .testTag("featured-sections"),
    ) {
        itemsIndexed(rows, key = { i, r -> "$i:${r.heading}" }) { s, row ->
            Column {
                Text(
                    row.heading, style = Type.label, color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    modifier = Modifier.padding(start = m.inset, top = if (s == 0) 0.dp else 14.dp, bottom = 14.dp).testTag("shelf-heading:$s"),
                )
                LazyRow(
                    state = rowStates[s],
                    // The space under the cards is the row's own so a focused card's shadow is not clipped in a step.
                    contentPadding = PaddingValues(start = m.inset, end = m.inset, bottom = if (s == rows.lastIndex) 34.dp else 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(m.gutter * 0.8f),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp).stopAtRowEnds().testTag("featured-row:$s"),
                ) {
                    itemsIndexed(row.items, key = { _, it -> it.id }) { i, card ->
                        var cardFocused by remember(card.id) { mutableStateOf(false) }
                        Column(Modifier.width(CARD_WIDTH)) {
                            FocusTile(
                                label = listOfNotNull(card.title, card.subtitle).joinToString(", "),
                                onClick = { onOpen(card) },
                                shape = RoundedCornerShape(Metrics().tileRadius),
                                edgeLight = true,
                                onFocusChange = {
                                    cardFocused = it
                                    // Whichever card gains focus owns it; a loss reported after that gain must not clear it.
                                    val me = s * 1000L + i
                                    if (it) cursor.focusedCard = me else if (cursor.focusedCard == me) cursor.focusedCard = -1L
                                    if (it) { at[0] = s; at[1] = i; memory.remember(s, i); active = s; cursor.row = s; onSelect(card) }
                                },
                                modifier = Modifier
                                    .width(CARD_WIDTH)
                                    .height(CARD_HEIGHT)
                                    .focusRequester(requester(s, i))
                                    .then(if (s == active && card.id == selectedId) Modifier.focusRequester(firstCard) else Modifier)
                                    .testTag("featured:$s:${card.id}"),
                            ) { art(card, cardFocused) }
                            CardTitle(
                                card.title, focused = cardFocused,
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("featured-title:$s:${card.id}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val CARD_WIDTH = 150.dp
private val CARD_HEIGHT = 84.dp
