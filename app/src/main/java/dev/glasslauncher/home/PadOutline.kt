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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
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

/** Where each part of the drawn device is, in dp inside a [w] x [h] box. Pure data, so the layout test and the drawing read the same numbers. */
internal object OutlineGeometry {
    const val PAD_W = 300f
    const val PAD_H = 200f
    const val REMOTE_W = 140f
    const val REMOTE_H = 270f

    /** How much the drawing is enlarged on the page (the callouts and lines use the same factor). */
    fun scale(style: PadStyle) = if (style == PadStyle.Remote) 1.1f else 1.4f

    /** The drawing's size on the page, scale included. */
    fun size(style: PadStyle) = (if (style == PadStyle.Remote) Offset(REMOTE_W, REMOTE_H) else Offset(PAD_W, PAD_H)) * scale(style)

    /** Anchors by slot id: where a callout's line meets the drawing. */
    fun anchors(style: PadStyle): Map<String, Offset> = when (style) {
        PadStyle.Remote -> mapOf(
            "app1" to Offset(42f, 176f), "app2" to Offset(98f, 176f),
            "app3" to Offset(42f, 212f), "app4" to Offset(98f, 212f),
            "recents" to Offset(42f, 248f), "settings" to Offset(98f, 248f),
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
                "pad:mode" to if (ps) Offset(150f, 130f) else Offset(150f, 58f),
                "pad:thumbl" to left, "stick:left" to left, "pad:thumbr" to right, "stick:right" to right,
            )
        }
    }

    /** [anchors] on the page, scale included. */
    fun placed(style: PadStyle): Map<String, Offset> = anchors(style).mapValues { it.value * scale(style) }

    /** The D-pad's centre (a controller only): not mappable, but drawn and lit. */
    fun dpad(style: PadStyle) = if (style == PadStyle.PlayStation) Offset(82f, 80f) else Offset(120f, 130f)
}

/** What the drawing needs to light and mark: the slots held now, the ones the user mapped, the callout in focus, and the live pad. */
internal data class OutlineState(
    val lit: Set<String> = emptySet(),
    val mapped: Set<String> = emptySet(),
    val focused: String? = null,
    val pad: PadState = PadState(),
)

/** The controller or remote as thin line art; sized by [OutlineGeometry.size]. Parts light while held, a mapped part wears a ring, the focused one a bright ring. */
@Composable
internal fun PadOutline(style: PadStyle, state: OutlineState, ink: Color, accent: Color, modifier: Modifier = Modifier) {
    val k = OutlineGeometry.scale(style)
    Canvas(modifier) { scale(k, k, pivot = Offset.Zero) { drawOutline(this, style, state, ink, accent) } }
}

private fun drawOutline(scope: DrawScope, style: PadStyle, st: OutlineState, ink: Color, accent: Color) = with(scope) {
    fun px(v: Float) = v.dp.toPx()
    fun at(o: Offset) = Offset(px(o.x), px(o.y))
    val rim = ink.copy(alpha = 0.55f)
    val faint = ink.copy(alpha = 0.18f)
    val lit = ink.copy(alpha = 0.92f)
    val line = Stroke(px(1.5f), cap = StrokeCap.Round)
    val thin = Stroke(px(1f), cap = StrokeCap.Round)
    fun on(id: String) = id in st.lit
    fun ring(id: String, c: Offset, r: Float) {
        if (id in st.mapped) drawCircle(accent, px(r + 4f), at(c), style = Stroke(px(2f)))
        if (id == st.focused) drawCircle(Color.White, px(r + 7f), at(c), style = Stroke(px(2.5f)))
    }
    if (style == PadStyle.Remote) {
        val w = OutlineGeometry.REMOTE_W; val h = OutlineGeometry.REMOTE_H
        drawRoundRect(faint, Offset.Zero, Size(px(w), px(h)), CornerRadius(px(36f)))
        drawRoundRect(rim, Offset.Zero, Size(px(w), px(h)), CornerRadius(px(36f)), style = line)
        drawCircle(rim, px(46f), at(Offset(70f, 56f)), style = thin); drawCircle(rim, px(18f), at(Offset(70f, 56f)), style = thin)
        for (c in listOf(Offset(36f, 112f), Offset(104f, 112f), Offset(70f, 126f), Offset(36f, 150f), Offset(70f, 150f), Offset(104f, 150f))) drawCircle(rim, px(11f), at(c), style = thin)
        for ((id, c) in OutlineGeometry.anchors(style)) {
            drawCircle(if (on(id)) lit else faint, px(11f), at(c)); drawCircle(rim, px(11f), at(c), style = thin); ring(id, c, 11f)
        }
        return@with
    }
    val ps = style == PadStyle.PlayStation
    val a = OutlineGeometry.anchors(style)
    // The body: a wide rounded shape with two grips, one stroke.
    val body = Path().apply {
        moveTo(px(70f), px(40f))
        cubicTo(px(40f), px(40f), px(10f), px(120f), px(8f), px(160f))
        cubicTo(px(6f), px(190f), px(40f), px(200f), px(60f), px(180f))
        lineTo(px(100f), px(135f))
        quadraticBezierTo(px(150f), px(122f), px(200f), px(135f))
        lineTo(px(240f), px(180f))
        cubicTo(px(260f), px(200f), px(294f), px(190f), px(292f), px(160f))
        cubicTo(px(290f), px(120f), px(260f), px(40f), px(230f), px(40f))
        quadraticBezierTo(px(150f), px(28f), px(70f), px(40f))
        close()
    }
    drawPath(body, faint); drawPath(body, rim, style = line)
    // Shoulders and triggers: a bumper along the top edge, a trigger above it; filled while held, the trigger by how far it is pulled.
    fun shoulder(id: String, x: Float, y: Float, w: Float, h: Float, r: Float, amount: Float = 0f) {
        drawRoundRect(if (on(id)) lit else faint, Offset(px(x), px(y)), Size(px(w), px(h)), CornerRadius(px(r)))
        if (amount > 0f && !on(id)) drawRoundRect(lit, Offset(px(x), px(y)), Size(px(w * amount), px(h)), CornerRadius(px(r)))
        drawRoundRect(rim, Offset(px(x), px(y)), Size(px(w), px(h)), CornerRadius(px(r)), style = thin)
    }
    shoulder("pad:l2", 62f, 4f, 48f, 15f, 5f, st.pad.leftTrigger); shoulder("pad:r2", 190f, 4f, 48f, 15f, 5f, st.pad.rightTrigger)
    shoulder("pad:l1", 52f, 23f, 72f, 13f, 6f); shoulder("pad:r1", 176f, 23f, 72f, 13f, 6f)
    // Sticks with the live dot; a held stick press fills the ring.
    fun stick(id: String, press: String, x: Float, y: Float, c: Offset) {
        val at0 = at(c)
        drawCircle(if (on(press)) lit else faint, px(OUTLINE_STICK_R), at0); drawCircle(rim, px(OUTLINE_STICK_R), at0, style = line)
        drawCircle(if (on(press)) faint else lit, px(6f), Offset(at0.x + x * px(OUTLINE_STICK_R - 7f), at0.y + y * px(OUTLINE_STICK_R - 7f)))
        ring(id, c, OUTLINE_STICK_R); ring(press, c, OUTLINE_STICK_R + 4f)
    }
    stick("stick:left", "pad:thumbl", st.pad.leftX, st.pad.leftY, a.getValue("stick:left"))
    stick("stick:right", "pad:thumbr", st.pad.rightX, st.pad.rightY, a.getValue("stick:right"))
    // The D-pad: a cross whose arm lights with its direction.
    val d = OutlineGeometry.dpad(style)
    fun arm(name: String, dx: Float, dy: Float, w: Float, h: Float, held: Boolean) {
        val c = at(Offset(d.x + dx, d.y + dy))
        drawRoundRect(if (held) lit else faint, Offset(c.x - px(w / 2), c.y - px(h / 2)), Size(px(w), px(h)), CornerRadius(px(5f)))
        drawRoundRect(rim, Offset(c.x - px(w / 2), c.y - px(h / 2)), Size(px(w), px(h)), CornerRadius(px(5f)), style = thin)
    }
    val p = st.pad
    arm("Up", 0f, -19f, 22f, 24f, "Up" in p.pressed || p.hatY < 0); arm("Down", 0f, 19f, 22f, 24f, "Down" in p.pressed || p.hatY > 0)
    arm("Left", -19f, 0f, 24f, 22f, "Left" in p.pressed || p.hatX < 0); arm("Right", 19f, 0f, 24f, 22f, "Right" in p.pressed || p.hatX > 0)
    // The four face buttons, each with its symbol (PlayStation) or letter drawn on it by the callouts, not here.
    for (id in listOf("pad:y", "pad:x", "pad:b", "pad:a")) {
        val c = a.getValue(id)
        drawCircle(if (on(id)) lit else faint, px(11f), at(c)); drawCircle(rim, px(11f), at(c), style = line)
        if (ps) drawSymbol(this, id.removePrefix("pad:"), at(c), px(5f), if (on(id)) faint else rim)
        ring(id, c, 11f)
    }
    // The small buttons, the home button, and the PlayStation touchpad.
    if (ps) {
        drawRoundRect(faint, Offset(px(125f), px(42f)), Size(px(50f), px(34f)), CornerRadius(px(7f)))
        drawRoundRect(rim, Offset(px(125f), px(42f)), Size(px(50f), px(34f)), CornerRadius(px(7f)), style = thin)
    }
    for (id in listOf("pad:select", "pad:start")) {
        val c = a.getValue(id)
        drawRoundRect(if (on(id)) lit else faint, Offset(at(c).x - px(9f), at(c).y - px(5f)), Size(px(18f), px(10f)), CornerRadius(px(5f)))
        drawRoundRect(rim, Offset(at(c).x - px(9f), at(c).y - px(5f)), Size(px(18f), px(10f)), CornerRadius(px(5f)), style = thin)
        ring(id, c, 9f)
    }
    a.getValue("pad:mode").let { c ->
        drawCircle(if (on("pad:mode")) lit else faint, px(8f), at(c)); drawCircle(rim, px(8f), at(c), style = thin); ring("pad:mode", c, 8f)
    }
    for (id in listOf("pad:l1", "pad:r1", "pad:l2", "pad:r2")) ring(id, a.getValue(id), 6f)
}

private const val OUTLINE_STICK_R = 18f

/** One PlayStation face symbol (cross, circle, square, triangle) as strokes around [c], [r] px from the centre to its edge. Own drawing, not Sony's. */
internal fun drawSymbol(scope: DrawScope, face: String, c: Offset, r: Float, color: Color) = with(scope) {
    val s = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
    when (face) {
        "a" -> { drawLine(color, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), 1.6.dp.toPx(), StrokeCap.Round); drawLine(color, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), 1.6.dp.toPx(), StrokeCap.Round) }
        "b" -> drawCircle(color, r * 1.05f, c, style = s)
        "x" -> drawRect(color, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = s)
        else -> drawPath(Path().apply { moveTo(c.x, c.y - r * 1.1f); lineTo(c.x + r * 1.1f, c.y + r * 0.8f); lineTo(c.x - r * 1.1f, c.y + r * 0.8f); close() }, color, style = s)
    }
}

/** A PlayStation face symbol at [size] for a callout. */
@Composable
internal fun SymbolMark(face: String, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawSymbol(this, face, center, this.size.minDimension * 0.28f, color) }
}
