package dev.glasslauncher.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
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
    internal val last = HashMap<String, Pair<Offset, Int>>()

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
    return this
        .onGloballyPositioned { c ->
            val now = c.positionInRoot()
            val before = tracker.last[key]
            tracker.last[key] = now to tracker.generation
            if (before != null && before.second < tracker.generation) {
                // Where it was drawn (its old spot, minus any glide still under way) relative to where it is now.
                val from = before.first - now + slide.value
                if (from != Offset.Zero) scope.launch { slide.snapTo(from); slide.animateTo(Offset.Zero, GlideSpec) }
            }
        }
        // A layout offset, not a graphicsLayer: a layer would clip the tiles' shadows at rest.
        .offset { IntOffset(slide.value.x.roundToInt(), slide.value.y.roundToInt()) }
}
