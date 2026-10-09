package dev.glasslauncher.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.glasslauncher.R
import dev.glasslauncher.data.appKey
import dev.glasslauncher.glass.GlassStyle
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.glass
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Safe
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type

/** One remote key a hint refers to. */
internal enum class HintKey(val words: String) {
    Arrows("Arrow keys"), LeftRight("Left or Right"), Up("Up"), Down("Down"), Select("Select"),
}

/** A hint: the key to press and what it does. */
internal data class MoveHint(val key: HintKey, val label: String)

/** The hints for what is being moved: in the tray, in the grid, or inside an open folder. */
internal fun moveHints(inDock: Boolean, inFolder: Boolean): List<MoveHint> = when {
    inFolder -> listOf(MoveHint(HintKey.Arrows, "Rearrange"), MoveHint(HintKey.Select, "Done"))
    inDock -> listOf(MoveHint(HintKey.LeftRight, "Rearrange"), MoveHint(HintKey.Down, "Move to Apps"), MoveHint(HintKey.Select, "Done"))
    else -> listOf(MoveHint(HintKey.Arrows, "Move"), MoveHint(HintKey.Up, "Add to Top Row"), MoveHint(HintKey.Select, "Done"))
}

/**
 * tvOS shows guidance while rearranging; without it the wiggle mode feels like a dead end. Each hint is a key
 * glyph in a small chip and a short label, in one dense glass capsule (a scrim under the glass keeps it legible
 * over whatever tiles are behind it).
 */
@Composable
internal fun MoveBanner(key: String, layout: HomeLayout, modifier: Modifier = Modifier, inFolder: Boolean = false) {
    val palette = LocalPalette.current
    val inDock = layout.dock.any { appKey(it.packageName) == key }
    val scrim = if (palette.light) Color.White.copy(alpha = 0.72f) else Color.Black.copy(alpha = 0.62f)
    Row(
        modifier
            .padding(bottom = Safe.bottom)
            // The hints change as the app moves between the tray and the grid: grow, don't jump.
            .animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))
            .clip(Shapes.pill)
            .glass(LocalBackdrop.current, Shapes.pill, GlassStyle.panel(palette.light))
            .background(scrim, Shapes.pill)
            .padding(horizontal = 26.dp, vertical = 12.dp)
            .testTag("move-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        moveHints(inDock, inFolder).forEach { hint ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier
                    .testTag("move-hint:${hint.label}")
                    // The glyph chips are pictures: say the key in words for TalkBack.
                    .semantics(mergeDescendants = true) { contentDescription = "${hint.key.words}: ${hint.label}" },
            ) {
                KeyChip(hint.key)
                androidx.compose.foundation.text.BasicText(
                    hint.label,
                    style = Type.secondary.copy(color = palette.secondary),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The key as a small chip: arrow glyphs (one symbol set, rotated), or "Select" as a word in its own chip. */
@Composable
private fun RowScope.KeyChip(key: HintKey) {
    val palette = LocalPalette.current
    val fill = palette.primary.copy(alpha = 0.16f)
    Row(
        // At least 26 dp, growing with the text at larger sizes.
        Modifier.defaultMinSize(minHeight = 26.dp).clip(RoundedCornerShape(8.dp)).background(fill).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        when (key) {
            HintKey.Select -> androidx.compose.foundation.text.BasicText("Select", style = Type.caption.copy(color = palette.primary))
            HintKey.Arrows -> listOf(180f, 0f, 270f, 90f).forEach { Arrow(it) }
            HintKey.LeftRight -> listOf(180f, 0f).forEach { Arrow(it) }
            HintKey.Up -> Arrow(270f)
            HintKey.Down -> Arrow(90f)
        }
    }
}

@Composable
private fun Arrow(degrees: Float) {
    val palette = LocalPalette.current
    Image(
        painterResource(R.drawable.ic_play_arrow), null,
        colorFilter = ColorFilter.tint(palette.primary),
        modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = degrees },
    )
}
