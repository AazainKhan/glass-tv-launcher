package dev.glasslauncher.home

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp

/**
 * One Control Center open's choreography (P49, see [CcMorph]): the progress every shape follows and where the
 * drops are. Shapes read [p] while drawing, so each redraws as the spring moves and is idle once it lands.
 * Positions are window pixels, recorded as things are laid out (before the first frame draws).
 */
@androidx.compose.runtime.Stable
internal class CcMotion(private val progress: () -> Float, private val on: () -> Boolean) {
    /** Progress now (0 = the pill, 1 = open). Reading it from a draw observes the spring. */
    val p: Float get() = progress()

    /** False with Reduce Motion: every shape stays put and Control Center simply fades. */
    val enabled: Boolean get() = on()

    /** Home's pill, when Control Center opens over Home (the drops start as its two ends); null over an app. */
    var pill: Rect? = null

    /** The page icons' slots: 0 = Controls, 1 = Alexa (none when the Alexa page is off). */
    val icons = arrayOfNulls<Rect>(2)

    /** The panel (its column of controls), for a lone drop's spot and the stagger's reach. */
    var panel: Rect? = null

    /** The spot a lone drop settles on when there are no page icons: where the icons would be. */
    var loneSpot: Rect? = null

    /** The drops the controls grow out of: the page icons' centres, or the lone drop's. */
    fun drops(): List<Rect> = icons.filterNotNull().ifEmpty { listOfNotNull(loneSpot) }

    /** The drop a control at [slot] grows out of: the nearer one by column. */
    fun dropFor(slot: Rect): Rect? = drops().minByOrNull { kotlin.math.abs(it.center.x - slot.center.x) }

    /** The farthest any control's centre is from its drop: the stagger's reach. */
    fun reach(): Float {
        val panel = panel ?: return 1f
        val d = drops().firstOrNull() ?: return 1f
        return maxOf((panel.bottomLeft - d.center).getDistance(), (panel.bottomRight - d.center).getDistance(), 1f)
    }
}

/** What a shape is in the choreography: a page icon (0 = Controls, 1 = Alexa), a control, or the header. */
internal sealed interface CcRole {
    data class Icon(val index: Int) : CcRole
    data object Control : CcRole
    data object Header : CcRole
}

/**
 * One shape's part in the choreography: where its slot is and, from that and [motion], where it is drawn this
 * frame ([now]). Shared by the shape's outer transform ([ccEmerge]) and its glass ([ccSurface]), which maps the
 * sheet back through the same transform so the glass always shows what is behind its current spot.
 */
@androidx.compose.runtime.Stable
internal class CcEmerge(val motion: CcMotion, val role: CcRole) {
    /** The slot, in window pixels (as laid out, without this transform). */
    var slot: Rect? = null

    /** The frame for progress [p] (null = drawn where it is: at rest, or with Reduce Motion). */
    fun now(p: Float = motion.p): CcEmergeFrame? {
        if (!motion.enabled) return null
        val slot = slot ?: return null
        val frame = when (role) {
            is CcRole.Icon -> {
                val pill = motion.pill
                val q = CcMorph.split(p)
                if (pill != null) {
                    val (left, right) = CcMorph.pillEnds(pill)
                    val (c, d) = CcMorph.drop(q, if (role.index == 0) left else right, pill.height, slot)
                    CcEmergeFrame(d / minOf(slot.width, slot.height), c.x - slot.center.x, c.y - slot.center.y, 1f)
                } else {
                    // Over an app there is no pill: the drop materialises at the icon's spot.
                    CcMorph.emerge(q, slot, slot.center, minOf(slot.width, slot.height) * OVER_APP_START)
                }
            }
            CcRole.Control -> {
                val drop = motion.dropFor(slot) ?: return null
                val start = CcMorph.tileStart((slot.center - drop.center).getDistance(), motion.reach())
                CcMorph.emerge(CcMorph.tile(p, start), slot, drop.center, minOf(drop.width, drop.height))
            }
            CcRole.Header -> {
                // The time grows out of the pill's clock (its left part); over an app, from a little smaller in place.
                val pill = motion.pill
                val from = pill?.let { Offset(it.left + it.width * 0.38f, it.center.y) } ?: slot.center
                val e = CcMorph.header(p)
                val s0 = if (pill != null) (pill.height * 0.9f / slot.height).coerceIn(0.2f, 1f) else OVER_APP_START
                CcEmergeFrame(s0 + (1f - s0) * e, (from.x - slot.center.x) * (1f - e), (from.y - slot.center.y) * (1f - e), 1f)
            }
        }
        return frame.takeUnless { it.still }
    }

    private companion object {
        /** Over an app, shapes grow from this fraction of their size, in place. */
        const val OVER_APP_START = 0.3f
    }
}

internal val LocalCcMotion = androidx.compose.runtime.staticCompositionLocalOf<CcMotion?> { null }

/**
 * The glass between the shapes while the pill splits: the neck joining the two drops (from the pill's whole
 * capsule at the start to nothing), or, with no page icons, the one drop the controls grow out of. Filled once
 * with the open's sheet ([brush], window coordinates), no blur and no layer. The drops themselves are the page
 * icons, drawn by the panel.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCcDrops(motion: CcMotion, brush: androidx.compose.ui.graphics.Brush, icons: Boolean) {
    val p = motion.p
    val q = CcMorph.split(p)
    val pill = motion.pill
    if (icons) {
        val pill = pill ?: return
        val i0 = motion.icons[0] ?: return
        val i1 = motion.icons[1] ?: return
        val (l, r) = CcMorph.pillEnds(pill)
        val (c0, d0) = CcMorph.drop(q, l, pill.height, i0)
        val (c1, d1) = CcMorph.drop(q, r, pill.height, i1)
        val (ends, waist) = CcMorph.neck(q, minOf(d0, d1) / 2) ?: return
        val mx = (c0.x + c1.x) / 2; val my = (c0.y + c1.y) / 2
        val bow = ends - 2 * waist
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(c0.x, c0.y - ends)
            quadraticTo(mx, my + bow, c1.x, c1.y - ends)
            lineTo(c1.x, c1.y + ends)
            quadraticTo(mx, my - bow, c0.x, c0.y + ends)
            close()
        }
        drawPath(path, brush)
    } else {
        val spot = motion.loneSpot ?: return
        val from = pill?.let { CcMorph.pillEnds(it).second } ?: spot.center
        val fromD = pill?.height ?: 0f
        val (c, d) = CcMorph.drop(q, from, fromD, spot)
        val radius = d / 2 * CcMorph.loneDrop(p)
        if (radius < 0.5f) return
        drawCircle(brush, radius, c)
        drawCircle(CcMaterial.rimBrush(androidx.compose.ui.geometry.Size(radius * 2, radius * 2)), radius, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}

internal val LocalCcEmerge = androidx.compose.runtime.staticCompositionLocalOf<CcEmerge?> { null }

/**
 * Draws this shape where the choreography puts it this frame ([CcEmerge.now]): a draw transform, so its layout,
 * focus and hit area stay in its slot. It records the slot as it is laid out.
 */
internal fun Modifier.ccEmerge(emerge: CcEmerge?): Modifier = if (emerge == null) this else this then CcEmergeElement(emerge)

private data class CcEmergeElement(val emerge: CcEmerge) : ModifierNodeElement<CcEmergeNode>() {
    override fun create() = CcEmergeNode(emerge)
    override fun update(node: CcEmergeNode) { node.emerge = emerge; node.invalidateDraw() }
}

private class CcEmergeNode(var emerge: CcEmerge) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInWindow()
        val s = coordinates.size
        val r = Rect(p.x, p.y, p.x + s.width, p.y + s.height)
        if (emerge.slot != r) { emerge.slot = r; register(r); invalidateDraw() }
    }

    private fun register(r: Rect) {
        val role = emerge.role
        if (role is CcRole.Icon) emerge.motion.icons[role.index] = r
    }

    override fun ContentDrawScope.draw() {
        val f = emerge.now() ?: return drawContent()
        translate(f.dx, f.dy) { scale(f.scale, f.scale, pivot = center) { this@draw.drawContent() } }
    }
}
