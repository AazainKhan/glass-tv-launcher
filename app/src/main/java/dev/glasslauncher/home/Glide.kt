package dev.glasslauncher.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.CoroutineStart
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Remembers where each home cell was last placed, so a cell that moves (move mode: a swap along a row, a
 * hop between rows, the tray pushing its rightmost app down) glides from its old spot instead of jumping.
 * [generation] is bumped when the layout changes in move mode; only a cell positioned in a new generation
 * glides, so scrolling the list (which also shifts every position) never animates.
 */
class GlideTracker {
    var generation = 0
    /** Cells being taken off Home that are fading out in place (see [leaveAlpha]) before the others glide. */
    var leaving by mutableStateOf<Set<String>>(emptySet())
    /** 1 → 0 while [leaving] fades; read in the draw phase only. */
    val leaveAlpha = Animatable(1f)
    internal val last = HashMap<String, Pair<Offset, Int>>()
    /** A tile's width as last laid out (all cells of a row share it): a cell new to its row has none of its own yet. */
    internal var tileWidth = 0f
    /**
     * Where each cell will be placed after the layout change just made, worked out from slot positions (the cell
     * now in slot i goes where slot i's old occupant was drawn). A cell applies its glide offset from this in the
     * frame it first composes in its new place; waiting for its measured position put it at the final spot for a
     * frame before the offset landed, so it popped in there and then jumped back (P62 on the stick).
     */
    var predicted: Map<String, Offset> = emptyMap()

    companion object {
        /** Tests only: how many glides changed rows (drawn beneath their row's other tiles). */
        @androidx.annotation.VisibleForTesting @Volatile var wrapGlides = 0
        /** Tests only: how many times Home's list has been handed a layout (a hide's write landing is one). */
        @androidx.annotation.VisibleForTesting @Volatile var layoutsSeen = 0
        /** Tests only: glides started from a predicted position (in the composing frame) and from a measured one (a frame late). */
        @androidx.annotation.VisibleForTesting @Volatile var predictedGlides = 0
        @androidx.annotation.VisibleForTesting @Volatile var measuredGlides = 0
    }

    /** Forgets where cells were: positions kept from before a scroll would make the next move glide from the wrong place. */
    fun reset() { last.clear() }
}

/** How long the glide stays on after a move ends, so the last placement finishes instead of snapping (see GlideSpec). */
const val GLIDE_TAIL_MS = 600L

val LocalGlide = compositionLocalOf<GlideTracker?> { null }

/** How long a wrapping tile takes to slide out past the end of its old row. */
private const val WRAP_OUT_MS = 200

private val GlideSpec = spring<Offset>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = Offset(0.5f, 0.5f))

/** Place the cell [key] with a glide from where it was last drawn (see [GlideTracker]). */
@Composable
fun Modifier.glide(key: String): Modifier {
    val tracker = LocalGlide.current ?: return this
    val slide = remember(key) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    // A cell that wraps to another row (the first of a row going up, the last going down) slides out past the end
    // of its old row and in from beyond the end of its new row, like tvOS: it never crosses the other tiles.
    var wrapping by remember(key) { mutableStateOf(false) }
    // The wrap's geometry for its fade: [0] where the exit started (x, relative to the new place), [1] how far past
    // the row's edge it is fully gone, [2] 0 while exiting, 1 while entering. [3] the tile's width, from layout.
    val wrap = remember(key) { floatArrayOf(0f, 1f, 0f, 0f) }
    /** Runs the glide for [from]: a plain one, or a wrap's exit and entry when it changes row and column. */
    suspend fun run(from: Offset, predictedStart: Boolean) {
        val tile = if (wrap[3] > 0f) wrap[3] else tracker.tileWidth
        // Positions carry float noise (6e-5 px): a row change is a real vertical distance.
        val changesRow = kotlin.math.abs(from.y) > 1f
        val wraps = changesRow && tile > 0f && kotlin.math.abs(from.x) > tile * 0.5f
        wrapping = wraps
        if (changesRow) GlideTracker.wrapGlides++
        if (predictedStart) GlideTracker.predictedGlides++ else GlideTracker.measuredGlides++
        slide.snapTo(from)
        try {
            if (wraps) {
                // Old place is to the right of the new one: it was at the end of its row and goes on to the start of
                // the next (out to the right, in from the left); and the other way round.
                val sign = if (from.x > 0f) 1f else -1f
                val gap = tile * 1.15f
                wrap[0] = from.x; wrap[1] = gap; wrap[2] = 0f
                slide.animateTo(Offset(from.x + sign * gap, from.y), tween(WRAP_OUT_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                wrap[2] = 1f
                slide.snapTo(Offset(-sign * gap, 0f))
                slide.animateTo(Offset.Zero, GlideSpec)
            } else slide.animateTo(Offset.Zero, GlideSpec)
        } finally { if (slide.value == Offset.Zero) wrapping = false }
    }
    // The offset goes on in the frame the cell first composes in its new place, before it is laid out there.
    val predicted = tracker.predicted[key]
    if (predicted != null) SideEffect {
        val before = tracker.last[key]
        if (before != null && before.second < tracker.generation) {
            tracker.last[key] = predicted to tracker.generation
            val from = before.first - predicted + slide.value
            if (from != Offset.Zero) scope.launch(start = CoroutineStart.UNDISPATCHED) { run(from, true) }
        }
    }
    return this
        .zIndex(if (wrapping) -1f else 0f)
        // Before the layers below: the position it records is the slot's, not the shrunk or faded one's.
        .onGloballyPositioned { c ->
            wrap[3] = c.size.width.toFloat()
            tracker.tileWidth = wrap[3]
            val now = c.positionInRoot()
            val before = tracker.last[key]
            tracker.last[key] = now to tracker.generation
            if (before != null && before.second < tracker.generation) {
                // Where it was drawn (its old spot, minus any glide still under way) relative to where it is now.
                val from = before.first - now + slide.value
                if (from != Offset.Zero) scope.launch { run(from, false) }
            }
        }
        // A tile taken off Home fades and shrinks a little where it stands before the others close the gap. Only
        // that tile gets a layer (a layer on every cell would clip the shadows at rest).
        .then(
            if (key in tracker.leaving) Modifier.graphicsLayer {
                val a = tracker.leaveAlpha.value
                alpha = a
                val sc = 0.9f + 0.1f * a
                scaleX = sc; scaleY = sc
            } else Modifier,
        )
        // Only as it passes the row's edge does a wrapping tile fade; inside the grid it is solid.
        .then(
            if (wrapping) Modifier.graphicsLayer {
                val out = if (wrap[2] == 0f) kotlin.math.abs(slide.value.x - wrap[0]) else kotlin.math.abs(slide.value.x)
                alpha = (1f - out / wrap[1]).coerceIn(0f, 1f)
            } else Modifier,
        )
        // A layout offset, not a graphicsLayer: a layer would clip the tiles' shadows at rest.
        .offset { IntOffset(slide.value.x.roundToInt(), slide.value.y.roundToInt()) }
}
