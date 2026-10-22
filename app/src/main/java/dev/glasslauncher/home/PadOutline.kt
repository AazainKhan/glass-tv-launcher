package dev.glasslauncher.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Which device the editor draws (P233): a PlayStation-style, Xbox-style or generic controller, or the Fire remote. Chosen from the input
 * device's vendor id (Sony 054c, Microsoft 045e), else from words in its name. The drawings are Glass's own line art, no maker's artwork.
 */
internal enum class PadStyle {
    PlayStation, Xbox, Generic, Remote;

    companion object {
        const val VENDOR_SONY = 0x054c
        const val VENDOR_MICROSOFT = 0x045e

        fun of(name: String, vendorId: Int?): PadStyle {
            val n = name.lowercase()
            return when {
                vendorId == VENDOR_SONY || "dualsense" in n || "dualshock" in n || "playstation" in n || "wireless controller" in n && "xbox" !in n -> PlayStation
                vendorId == VENDOR_MICROSOFT || "xbox" in n -> Xbox
                else -> Generic
            }
        }
    }

    /** The PlayStation face symbols stand for the Android A, B, X, Y of the same position (south, east, west, north). */
    val symbols get() = this == PlayStation
}

/** Where each part of the drawn device is, in the drawing's own units. Pure data, so the layout test and the drawing read the same numbers. */
internal object OutlineGeometry {
    const val PAD_W = 300f
    const val PAD_H = 200f
    /** The remote is the user's 400 x 800 SVG, cropped to its body (x 110..290, y 40..760) plus a margin. */
    const val REMOTE_W = 192f
    const val REMOTE_H = 732f
    const val REMOTE_X0 = 104f
    const val REMOTE_Y0 = 34f

    /** How much the drawing is enlarged on the page (the callouts and lines use the same factor). */
    fun scale(style: PadStyle) = if (style == PadStyle.Remote) 0.43f else 1.3333f

    /** The drawing's size on the page, scale included. */
    fun size(style: PadStyle) = (if (style == PadStyle.Remote) Offset(REMOTE_W, REMOTE_H) else Offset(PAD_W, PAD_H)) * scale(style)

    /** Anchors by slot id: where a callout's line meets the drawing. */
    fun anchors(style: PadStyle): Map<String, Offset> = when (style) {
        // In the SVG's own units shifted to the cropped box. The four app buttons are the SVG's; Recent Apps and Settings are added in its style.
        PadStyle.Remote -> mapOf(
            "app1" to Offset(160f - REMOTE_X0, 537f - REMOTE_Y0), "app2" to Offset(240f - REMOTE_X0, 537f - REMOTE_Y0),
            "app3" to Offset(160f - REMOTE_X0, 577f - REMOTE_Y0), "app4" to Offset(240f - REMOTE_X0, 577f - REMOTE_Y0),
            "recents" to Offset(145f - REMOTE_X0, 503f - REMOTE_Y0), "settings" to Offset(255f - REMOTE_X0, 503f - REMOTE_Y0),
        )
        else -> {
            val ps = style == PadStyle.PlayStation
            val left = if (ps) Offset(120f, 130f) else Offset(95f, 80f)
            val right = Offset(180f, 130f)
            val face = if (ps) Offset(218f, 80f) else Offset(225f, 80f)
            mapOf(
                "pad:l2" to Offset(86f, 12f), "pad:l1" to Offset(88f, 30f), "pad:r2" to Offset(214f, 12f), "pad:r1" to Offset(212f, 30f),
                "pad:y" to face + Offset(0f, -22f), "pad:x" to face + Offset(-22f, 0f), "pad:b" to face + Offset(22f, 0f), "pad:a" to face + Offset(0f, 22f),
                "pad:select" to if (ps) Offset(112f, 62f) else Offset(128f, 78f),
                "pad:start" to if (ps) Offset(188f, 62f) else Offset(172f, 78f),
                "pad:mode" to if (ps) Offset(150f, 118f) else Offset(150f, 58f),
                "pad:thumbl" to left, "stick:left" to left, "pad:thumbr" to right, "stick:right" to right,
            )
        }
    }

    /** [anchors] on the page, scale included. */
    fun placed(style: PadStyle): Map<String, Offset> = anchors(style).mapValues { it.value * scale(style) }

    /** The D-pad's centre (a controller only): not mappable, but drawn and lit. */
    fun dpad(style: PadStyle) = if (style == PadStyle.PlayStation) Offset(82f, 80f) else Offset(118f, 124f)
}

/** The drawing's colours: the user's teal on the dark theme, a darker teal on the light one (3.6:1 against the light page), brighter when held. */
internal object OutlineColors {
    fun stroke(light: Boolean) = if (light) Color(0xFF3A8288) else Color(0xFF519A9F)
    fun held(light: Boolean) = if (light) Color(0xFF14565C) else Color(0xFF8EE0E6)
}

/** What the drawing needs to light and mark: the slots held now, the ones the user mapped, the callout in focus, and the live pad. */
internal data class OutlineState(
    val lit: Set<String> = emptySet(),
    val mapped: Set<String> = emptySet(),
    val focused: String? = null,
    val pad: PadState = PadState(),
)

/**
 * The controller or remote as thin line art, in the style of the user's remote SVG: round caps and joins, a heavier stroke for the body
 * and rings, a lighter one at 0.8 opacity for the details, no fills except tiny symbols. A held button gets a brighter stroke and a soft
 * glow (never a fill); a mapped one a ring in [accent]; the focused one a ring in [focusInk]. Sized by [OutlineGeometry.size].
 */
@Composable
internal fun PadOutline(style: PadStyle, state: OutlineState, stroke: Color, held: Color, accent: Color, focusInk: Color, modifier: Modifier = Modifier) {
    val k = OutlineGeometry.scale(style)
    Canvas(modifier) { scale(k, k, pivot = Offset.Zero) { Painter(this, k, state, stroke, held, accent, focusInk).draw(style) } }
}

/** Heavy stroke (body, rings) and light stroke (details) in dp on screen, whatever the drawing's scale. */
private const val BODY_DP = 1.25f
private const val DETAIL_DP = 0.8f

private class Painter(val d: DrawScope, val k: Float, val st: OutlineState, val base: Color, val held: Color, val accent: Color, val focusInk: Color) {
    private fun u(v: Float) = with(d) { v.dp.toPx() }
    private fun stroke(dp: Float) = Stroke(u(dp / k), cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val body = stroke(BODY_DP)
    private val detail = stroke(DETAIL_DP)
    private val detailColor = base.copy(alpha = 0.8f)
    private fun on(id: String) = id in st.lit

    /** A part's stroke, lit or not: held gets a soft glow of two wider, fainter strokes under a brighter one. */
    private fun part(id: String?, heavy: Boolean = false, draw: (Color, Stroke) -> Unit) {
        val w = if (heavy) BODY_DP else DETAIL_DP
        if (id != null && on(id)) {
            draw(held.copy(alpha = 0.14f), stroke(w + 6f))
            draw(held.copy(alpha = 0.28f), stroke(w + 3f))
            draw(held, stroke(w + 0.7f))
        } else draw(if (heavy) base else detailColor, if (heavy) body else detail)
    }
    private fun circle(id: String?, c: Offset, r: Float, heavy: Boolean = false) = part(id, heavy) { col, s -> d.drawCircle(col, u(r), Offset(u(c.x), u(c.y)), style = s) }
    private fun rr(id: String?, x: Float, y: Float, w: Float, h: Float, r: Float, heavy: Boolean = false) =
        part(id, heavy) { col, s -> d.drawRoundRect(col, Offset(u(x), u(y)), Size(u(w), u(h)), CornerRadius(u(r)), style = s) }
    private fun path(id: String?, p: Path, heavy: Boolean = false) = part(id, heavy) { col, s -> d.drawPath(p, col, style = s) }
    private fun line(a: Offset, b: Offset, color: Color) = d.drawLine(color, Offset(u(a.x), u(a.y)), Offset(u(b.x), u(b.y)), strokeWidth = detail.width, cap = StrokeCap.Round)
    private fun dot(c: Offset, r: Float, color: Color = base) = d.drawCircle(color, u(r), Offset(u(c.x), u(c.y)))

    /** An SVG path string, drawn at this drawing's units. */
    private fun svgStroke(data: String, id: String? = null) {
        val p = PathParser().parsePathString(data).toPath()
        p.transform(androidx.compose.ui.graphics.Matrix().apply { scale(u(1f), u(1f)) })
        path(id, p)
    }

    /** The mapped ring and the focused ring around a button's anchor. */
    private fun marks(id: String, c: Offset, r: Float) {
        if (id in st.mapped) d.drawCircle(accent, u(r + 5f), Offset(u(c.x), u(c.y)), style = stroke(1.6f))
        if (id == st.focused) d.drawCircle(focusInk, u(r + 8f), Offset(u(c.x), u(c.y)), style = stroke(2.2f))
    }

    fun draw(style: PadStyle) = if (style == PadStyle.Remote) remote() else pad(style)

    // ── the remote: the user's SVG, shape for shape ─────────────────────────

    private fun remote() {
        d.translate(-u(OutlineGeometry.REMOTE_X0), -u(OutlineGeometry.REMOTE_Y0)) {
            rr(null, 110f, 40f, 180f, 720f, 35f, heavy = true)
            dot(Offset(200f, 70f), 2f)
            circle(null, Offset(145f, 85f), 14f); svgStroke("M 145 77 L 145 84 M 139 81 A 8 8 0 1 0 151 81")
            circle(null, Offset(200f, 130f), 18f); rr(null, 196f, 120f, 8f, 12f, 4f); svgStroke("M 192 128 A 8 8 0 0 0 208 128 M 200 136 L 200 142")
            circle(null, Offset(200f, 240f), 55f, heavy = true); circle(null, Offset(200f, 240f), 28f)
            circle(null, Offset(145f, 340f), 14f); svgStroke("M 139 336 L 147 336 A 4 4 0 0 1 147 344 L 141 344 M 139 336 L 142 333 M 139 336 L 142 339")
            circle(null, Offset(200f, 340f), 14f); svgStroke("M 193 342 L 193 337 L 200 332 L 207 337 L 207 342 Z")
            circle(null, Offset(255f, 340f), 14f); svgStroke("M 249 335 L 261 335 M 249 340 L 261 340 M 249 345 L 261 345")
            circle(null, Offset(145f, 390f), 14f); filledPoly(145f, 386f, 145f, 394f, 139f, 390f); filledPoly(151f, 386f, 151f, 394f, 145f, 390f)
            circle(null, Offset(200f, 390f), 14f); filledPoly(196f, 385f, 196f, 395f, 202f, 390f); filledRect(203f, 385f, 2f, 10f); filledRect(206f, 385f, 2f, 10f)
            circle(null, Offset(255f, 390f), 14f); filledPoly(255f, 386f, 255f, 394f, 261f, 390f); filledPoly(249f, 386f, 249f, 394f, 255f, 390f)
            circle(null, Offset(145f, 455f), 14f); svgStroke("M 139 452 L 143 452 L 147 448 L 147 462 L 143 458 L 139 458 Z"); svgStroke("M 137 447 L 153 463")
            rr(null, 187f, 425f, 26f, 60f, 13f); svgStroke("M 200 435 L 200 445 M 195 440 L 205 440"); svgStroke("M 195 470 L 205 470")
            circle(null, Offset(255f, 455f), 14f); rr(null, 248f, 450f, 14f, 10f, 1f); svgStroke("M 252 447 L 255 450 L 258 447"); svgStroke("M 249 463 L 261 463")
            // The four app buttons (the SVG's), then Recent Apps and Settings in the same style.
            for ((id, x, y) in listOf(Triple("app1", 130f, 525f), Triple("app2", 210f, 525f), Triple("app3", 130f, 565f), Triple("app4", 210f, 565f))) {
                rr(id, x, y, 60f, 24f, 8f); marks(id, Offset(x + 30f, y + 12f), 16f)
            }
            circle("recents", Offset(145f, 503f), 14f); rr(null, 139f, 497f, 9f, 9f, 2f); rr(null, 143f, 500f, 9f, 9f, 2f)
            circle("settings", Offset(255f, 503f), 14f); circle(null, Offset(255f, 503f), 4.5f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                line(Offset(255f + (6.5 * Math.cos(a)).toFloat(), 503f + (6.5 * Math.sin(a)).toFloat()), Offset(255f + (9f * Math.cos(a)).toFloat(), 503f + (9f * Math.sin(a)).toFloat()), detailColor)
            }
            marks("recents", Offset(145f, 503f), 14f); marks("settings", Offset(255f, 503f), 14f)
        }
    }

    private fun filledPoly(vararg p: Float) {
        val path = Path().apply { moveTo(u(p[0]), u(p[1])); var i = 2; while (i < p.size) { lineTo(u(p[i]), u(p[i + 1])); i += 2 }; close() }
        d.drawPath(path, base)
    }
    private fun filledRect(x: Float, y: Float, w: Float, h: Float) = d.drawRect(base, Offset(u(x), u(y)), Size(u(w), u(h)))

    // ── controllers ─────────────────────────────────────────────────────────

    private fun pad(style: PadStyle) {
        val ps = style == PadStyle.PlayStation
        val a = OutlineGeometry.anchors(style)
        // The body: one wide rounded outline with two grips.
        val outline = Path().apply {
            moveTo(u(70f), u(40f))
            cubicTo(u(40f), u(40f), u(10f), u(120f), u(8f), u(160f))
            cubicTo(u(6f), u(190f), u(40f), u(200f), u(60f), u(180f))
            lineTo(u(100f), u(160f))
            quadraticTo(u(150f), u(146f), u(200f), u(160f))
            lineTo(u(240f), u(180f))
            cubicTo(u(260f), u(200f), u(294f), u(190f), u(292f), u(160f))
            cubicTo(u(290f), u(120f), u(260f), u(40f), u(230f), u(40f))
            quadraticTo(u(150f), u(28f), u(70f), u(40f))
            close()
        }
        path(null, outline, heavy = true)
        // Shoulders and triggers; a pulled trigger also draws a line under itself as long as it is pulled.
        rr("pad:l2", 62f, 4f, 48f, 15f, 5f); rr("pad:r2", 190f, 4f, 48f, 15f, 5f)
        rr("pad:l1", 52f, 23f, 72f, 13f, 6f); rr("pad:r1", 176f, 23f, 72f, 13f, 6f)
        val p = st.pad
        if (p.leftTrigger > 0.05f) line(Offset(62f, 21f), Offset(62f + 48f * p.leftTrigger, 21f), held)
        if (p.rightTrigger > 0.05f) line(Offset(190f, 21f), Offset(190f + 48f * p.rightTrigger, 21f), held)
        // The D-pad: one stroked cross; an arm lights with its direction.
        val dp = OutlineGeometry.dpad(style)
        val cross = Path().apply {
            val pts = listOf(-9f to -25f, 9f to -25f, 9f to -9f, 25f to -9f, 25f to 9f, 9f to 9f, 9f to 25f, -9f to 25f, -9f to 9f, -25f to 9f, -25f to -9f, -9f to -9f)
            pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(u(dp.x + x), u(dp.y + y)) else lineTo(u(dp.x + x), u(dp.y + y)) }
            close()
        }
        path(null, cross)
        val dirs = mapOf(
            "Up" to (p.hatY < 0 || "Up" in p.pressed), "Down" to (p.hatY > 0 || "Down" in p.pressed),
            "Left" to (p.hatX < 0 || "Left" in p.pressed), "Right" to (p.hatX > 0 || "Right" in p.pressed),
        )
        fun armGlow(x: Float, y: Float, w: Float, h: Float) = part("held") { col, s -> d.drawRoundRect(col, Offset(u(x), u(y)), Size(u(w), u(h)), CornerRadius(u(3f)), style = s) }
        if (dirs.getValue("Up")) armGlow(dp.x - 9f, dp.y - 25f, 18f, 16f)
        if (dirs.getValue("Down")) armGlow(dp.x - 9f, dp.y + 9f, 18f, 16f)
        if (dirs.getValue("Left")) armGlow(dp.x - 25f, dp.y - 9f, 16f, 18f)
        if (dirs.getValue("Right")) armGlow(dp.x + 9f, dp.y - 9f, 16f, 18f)
        // Sticks: two concentric circles and the live dot; a pressed stick glows.
        fun stick(slot: String, press: String, x: Float, y: Float) {
            val c = a.getValue(slot)
            circle(press, c, 19f, heavy = true); circle(null, c, 9f)
            dot(Offset(c.x + x * 10f, c.y + y * 10f), 2.6f, if (on(press)) held else base)
            marks(slot, c, 19f); marks(press, c, 24f)
        }
        stick("stick:left", "pad:thumbl", p.leftX, p.leftY); stick("stick:right", "pad:thumbr", p.rightX, p.rightY)
        // Face buttons, each with its symbol or letter as thin strokes.
        for (id in listOf("pad:y", "pad:x", "pad:b", "pad:a")) {
            val c = a.getValue(id)
            circle(id, c, 10f)
            val col = if (on(id)) held else detailColor
            val centre = Offset(u(c.x), u(c.y))
            if (ps) drawSymbol(d, id.removePrefix("pad:"), centre, u(4.2f), col, u(DETAIL_DP / k))
            else drawLetter(d, id.removePrefix("pad:"), centre, u(1f), col, u(DETAIL_DP / k))
            marks(id, c, 10f)
        }
        // Touchpad (PlayStation), the small buttons, and the home button.
        if (ps) rr(null, 125f, 42f, 50f, 34f, 7f)
        for (id in listOf("pad:select", "pad:start")) {
            val c = a.getValue(id)
            if (ps) rr(id, c.x - 6f, c.y - 3f, 12f, 6f, 3f) else circle(id, c, 5f)
            marks(id, c, 8f)
        }
        a.getValue("pad:mode").let { c -> circle("pad:mode", c, if (ps) 6f else 8f); marks("pad:mode", c, 8f) }
        for (id in listOf("pad:l1", "pad:r1", "pad:l2", "pad:r2")) marks(id, a.getValue(id), 4f)
    }
}

/** One PlayStation face symbol (cross, circle, square, triangle) as thin strokes around [c], [r] px from the centre to its edge. Own drawing. */
internal fun drawSymbol(scope: DrawScope, face: String, c: Offset, r: Float, color: Color, width: Float = 1.2f * scope.density) = with(scope) {
    val s = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (face) {
        "a" -> { drawLine(color, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), width, StrokeCap.Round); drawLine(color, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), width, StrokeCap.Round) }
        "b" -> drawCircle(color, r * 1.05f, c, style = s)
        "x" -> drawRoundRect(color, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), CornerRadius(r * 0.15f), style = s)
        else -> drawPath(Path().apply { moveTo(c.x, c.y - r * 1.1f); lineTo(c.x + r * 1.1f, c.y + r * 0.8f); lineTo(c.x - r * 1.1f, c.y + r * 0.8f); close() }, color, style = s)
    }
}

/** An Xbox face letter (A, B, X, Y) as thin strokes, about 10 units tall ([unit] px per unit). */
private fun drawLetter(scope: DrawScope, face: String, c: Offset, unit: Float, color: Color, width: Float) = with(scope) {
    fun x(v: Float) = c.x + v * unit
    fun y(v: Float) = c.y + v * unit
    val path = Path()
    fun m(px: Float, py: Float) = path.moveTo(x(px), y(py))
    fun l(px: Float, py: Float) = path.lineTo(x(px), y(py))
    when (face) {
        "a" -> { m(-4f, 5f); l(0f, -5f); l(4f, 5f); m(-2.4f, 1.2f); l(2.4f, 1.2f) }
        "b" -> {
            m(-3f, -5f); l(-3f, 5f)
            m(-3f, -5f); l(1f, -5f); path.quadraticTo(x(4f), y(-2.5f), x(1f), y(0f)); l(-3f, 0f)
            m(1f, 0f); path.quadraticTo(x(4.6f), y(2.6f), x(1f), y(5f)); l(-3f, 5f)
        }
        "x" -> { m(-4f, -5f); l(4f, 5f); m(-4f, 5f); l(4f, -5f) }
        else -> { m(-4f, -5f); l(0f, 0f); l(4f, -5f); m(0f, 0f); l(0f, 5f) }
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A PlayStation face symbol at [size] for a callout. */
@Composable
internal fun SymbolMark(face: String, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawSymbol(this, face, center, this.size.minDimension * 0.28f, color) }
}

/** One callout's line: from its edge, a short horizontal run, then to the button. Points in dp inside the editor's box. */
internal data class Leader(val id: String, val onLeft: Boolean, val rowY: Float, val points: List<Offset>)

/** Where everything sits in the editor's box, from the box size. Pure, so the "no two lines cross" and "inside the safe area" rules are tests. */
internal data class OutlineLayout(val ox: Float, val oy: Float, val leftX: Float, val rightX: Float, val callout: Offset, val leaders: Map<String, Leader>) {
    companion object {
        const val CALLOUT_W = 176f
        const val CALLOUT_H = 32f
        const val MIN_PITCH = 34f
        const val STUB = 14f

        /** Which callouts sit on which side: centre buttons go to the side nearest them. */
        fun members(style: PadStyle): Pair<List<String>, List<String>> = when (style) {
            PadStyle.Remote -> listOf("recents", "app1", "app3") to listOf("settings", "app2", "app4")
            else -> listOf("pad:l2", "pad:l1", "pad:select", "pad:mode", "pad:thumbl", "stick:left") to
                listOf("pad:r2", "pad:r1", "pad:y", "pad:x", "pad:b", "pad:a", "pad:start", "pad:thumbr", "stick:right")
        }

        /** Each side top to bottom by where its button is on the drawing, so the lines run in the same order and don't cross. */
        fun ordered(style: PadStyle): Pair<List<String>, List<String>> {
            val a = OutlineGeometry.anchors(style)
            val (l, r) = members(style)
            // Same height: the outermost button first, so the line to the inner one runs under it and they don't cross.
            // (Heights within about 10 dp count as the same.)
            return l.sortedWith(compareBy({ Math.round(a.getValue(it).y / 10f) }, { a.getValue(it).x })) to
                r.sortedWith(compareBy({ Math.round(a.getValue(it).y / 10f) }, { -a.getValue(it).x }))
        }

        fun compute(style: PadStyle, width: Float, height: Float): OutlineLayout {
            val size = OutlineGeometry.size(style)
            val ox = (width - size.x) / 2f
            val oy = ((height - size.y) / 2f).coerceAtLeast(0f)
            val gap = ((width - size.x - 2 * CALLOUT_W) / 2f).coerceIn(16f, 60f)
            val leftX = ox - gap - CALLOUT_W
            val rightX = ox + size.x + gap
            val anchors = OutlineGeometry.placed(style)
            val (left, right) = ordered(style)
            val leaders = HashMap<String, Leader>()
            for ((ids, onLeft) in listOf(left to true, right to false)) {
                // Rows start at the button's own height and are pushed apart to the pitch, then back up if the last ran off the bottom.
                val ys = ids.map { oy + anchors.getValue(it).y }.toMutableList()
                for (i in 1 until ys.size) if (ys[i] < ys[i - 1] + MIN_PITCH) ys[i] = ys[i - 1] + MIN_PITCH
                val over = ys.last() + CALLOUT_H / 2 - height
                if (over > 0) for (i in ys.indices) ys[i] -= over
                for (i in ys.indices.reversed()) { if (i < ys.lastIndex && ys[i] > ys[i + 1] - MIN_PITCH) ys[i] = ys[i + 1] - MIN_PITCH }
                val shift = CALLOUT_H / 2 - ys.first()
                if (shift > 0) for (i in ys.indices) ys[i] += shift
                ids.forEachIndexed { i, id ->
                    val edge = Offset(if (onLeft) leftX + CALLOUT_W else rightX, ys[i])
                    val stub = Offset(edge.x + if (onLeft) STUB else -STUB, ys[i])
                    leaders[id] = Leader(id, onLeft, ys[i], listOf(edge, stub, Offset(ox + anchors.getValue(id).x, oy + anchors.getValue(id).y)))
                }
            }
            return OutlineLayout(ox, oy, leftX, rightX, Offset(CALLOUT_W, CALLOUT_H), leaders)
        }
    }
}
