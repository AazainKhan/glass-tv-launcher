package dev.glasslauncher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * tvOS's marquee (TVSPLabelAttribute enablesMarqueeWhenAncestorFocused): a one-line label that does not fit shows its start,
 * ends in an ellipsis, and scrolls right to left only while the control it sits in has focus. The scroll starts after a
 * delay, runs at a steady speed and pauses where the text loops; losing focus resets it to the start at once.
 * Values decompiled by REA from tvOS 26.6's MPUMarqueeView: a 3.0 s hold, then 30 pt/s (15 dp at this 960x540 scale); the gap before the
 * text repeats and the edge fade are owner-set there (not decoded), so GAP_DP and EDGE_FADE_DP are Glass's picks.
 */
object MarqueeSpec {
    const val START_DELAY_MS = 3_000
    const val PAUSE_AT_END_MS = 1_000
    const val SPEED_DP_PER_S = 15
    const val GAP_DP = 48
    const val EDGE_FADE_DP = 12
}

/** When a label scrolls (user, 2026-10-14): focused AND too long for its container, and motion is allowed. Otherwise it is still. */
fun marqueeScrolls(overflows: Boolean, active: Boolean, reduceMotion: Boolean) = overflows && active && !reduceMotion

/**
 * One shared marquee label for the whole app. The rule (user, 2026-10-14): it scrolls only when [active] (the focus of the ancestor
 * control) AND the text overflows its container; otherwise it shows the ellipsis. Reduced motion keeps it still. A label that fits is
 * plain text, with no layer and no motion.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(text: String, style: TextStyle, color: Color, active: Boolean, modifier: Modifier = Modifier) {
    var overflows by remember(text) { mutableStateOf(false) }
    // Not while Home is hidden by its idle fade (P226): an infinite scroll is a frame every 16 ms of nothing visible.
    val scrolls = marqueeScrolls(overflows, active && !dev.glasslauncher.widgets.LocalHomeHidden.current, LocalUiPrefs.current.reduceMotion)
    Text(
        text, style = style, color = color, maxLines = 1, softWrap = false,
        // Not focused: the ellipsis. Focused and too long: clipped, so the text can scroll under a soft edge.
        overflow = if (scrolls) TextOverflow.Clip else TextOverflow.Ellipsis,
        onTextLayout = { overflows = it.hasVisualOverflow || (overflows && active) },
        modifier = modifier.then(
            if (scrolls) Modifier
                .basicMarquee(
                    iterations = Int.MAX_VALUE, initialDelayMillis = MarqueeSpec.START_DELAY_MS,
                    repeatDelayMillis = MarqueeSpec.PAUSE_AT_END_MS, velocity = MarqueeSpec.SPEED_DP_PER_S.dp,
                    spacing = MarqueeSpacing(MarqueeSpec.GAP_DP.dp),
                )
                .edgeFade(MarqueeSpec.EDGE_FADE_DP)
            else Modifier,
        ),
    )
}

/** A soft fade over the last [widthDp] of the right edge (one DstIn gradient, only used while a label overflows). */
private fun Modifier.edgeFade(widthDp: Int): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = widthDp.dp.toPx()
        drawRect(
            Brush.horizontalGradient(0f to Color.Black, (1f - w / size.width).coerceAtLeast(0f) to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
