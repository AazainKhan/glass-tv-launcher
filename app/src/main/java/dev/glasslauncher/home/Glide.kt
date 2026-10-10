package dev.glasslauncher.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
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

    companion object {
        /** Tests only: how many glides changed rows (drawn beneath their row's other tiles). */
        @androidx.annotation.VisibleForTesting @Volatile var wrapGlides = 0
        /** Tests only: how many times Home's list has been handed a layout (a hide's write landing is one). */
        @androidx.annotation.VisibleForTesting @Volatile var layoutsSeen = 0
    }

    /** Forgets where cells were: positions kept from before a scroll would make the next move glide from the wrong place. */
    fun reset() { last.clear() }
}

/** How long the glide stays on after a move ends, so the last placement finishes instead of snapping (see GlideSpec). */
const val GLIDE_TAIL_MS = 600L

val LocalGlide = compositionLocalOf<GlideTracker?> { null }

private val GlideSpec = spring<Offset>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = Offset(0.5f, 0.5f))

/** Place the cell [key] with a glide from where it was last drawn (see [GlideTracker]). */
@Composable
fun Modifier.glide(key: String): Modifier {
    val tracker = LocalGlide.current ?: return this
    val slide = remember(key) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    // A cell that changes row (a wrap) glides beneath its row's other tiles, not across their faces.
    var lifted by remember(key) { mutableStateOf(false) }
    return this
        .zIndex(if (lifted) -1f else 0f)
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
        .onGloballyPositioned { c ->
            val now = c.positionInRoot()
            val before = tracker.last[key]
            tracker.last[key] = now to tracker.generation
            if (before != null && before.second < tracker.generation) {
                // Where it was drawn (its old spot, minus any glide still under way) relative to where it is now.
                val from = before.first - now + slide.value
                if (from != Offset.Zero) scope.launch {
                    lifted = from.y != 0f
                    slide.snapTo(from)
                    if (lifted) GlideTracker.wrapGlides++
                    try { slide.animateTo(Offset.Zero, GlideSpec) } finally { if (slide.value == Offset.Zero) lifted = false }
                }
            }
        }
        // A layout offset, not a graphicsLayer: a layer would clip the tiles' shadows at rest.
        .offset { IntOffset(slide.value.x.roundToInt(), slide.value.y.roundToInt()) }
}
