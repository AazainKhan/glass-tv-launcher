package dev.glasslauncher.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * Our own tile art for apps whose own icon reads as dead on an Apple TV-style row (P90): a rich gradient with a
 * crisp white glyph, drawn at the tile's size so it is sharp. Used when the user has set no icon of their own.
 */
object BuiltinTiles {
    /** Alexa's Smart Home app (the CC Alexa page's Smart Home tile is the same app). */
    private val SMART_HOME = setOf("com.amazon.smarthomemapviewapp", "com.amazon.alexa.smarthome", "com.amazon.smarthomedashboard")

    fun has(pkg: String) = pkg in SMART_HOME

    /** Draws [pkg]'s tile into [canvas] ([w] x [h]); false when there is none for it. */
    fun draw(canvas: Canvas, w: Int, h: Int, pkg: String): Boolean {
        if (pkg !in SMART_HOME) return false
        drawSmartHome(canvas, w.toFloat(), h.toFloat())
        return true
    }

    fun smartHome(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawSmartHome(Canvas(it), w.toFloat(), h.toFloat()) }

    private fun drawSmartHome(c: Canvas, w: Float, h: Float) {
        // Alexa blue: cyan at the top left to a deep blue (the user's pick, option A).
        val top = 0xFF2EE6FF.toInt(); val bottom = 0xFF1C3FD8.toInt(); val glow = 0xFF9BF4FF.toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Diagonal gradient with a soft light near the top left: depth without a flat plate.
        paint.shader = LinearGradient(0f, 0f, w * 0.55f, h, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(w * 0.28f, h * 0.18f, w * 0.55f, Color.argb(95, Color.red(glow), Color.green(glow), Color.blue(glow)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)

        // The glyph: a house with a lit window and three rays from its roof (a smart home), white, centred.
        val s = h * 0.52f                      // glyph height
        val cx = w / 2f; val cy = h * 0.54f
        val left = cx - s * 0.56f; val right = cx + s * 0.56f
        val roofTop = cy - s * 0.5f; val base = cy + s * 0.5f
        val eave = cy - s * 0.02f
        val house = Path().apply {
            moveTo(cx, roofTop)
            lineTo(right + s * 0.06f, eave)
            lineTo(right - s * 0.1f, eave)
            lineTo(right - s * 0.1f, base - s * 0.08f)
            quadTo(right - s * 0.1f, base, right - s * 0.2f, base)
            lineTo(left + s * 0.2f, base)
            quadTo(left + s * 0.1f, base, left + s * 0.1f, base - s * 0.08f)
            lineTo(left + s * 0.1f, eave)
            lineTo(left - s * 0.06f, eave)
            close()
        }
        // A soft shadow under the glyph, then the white glyph.
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(60, 0, 0, 40)
        c.save(); c.translate(0f, s * 0.035f); c.drawPath(house, paint); c.restore()
        paint.color = Color.WHITE
        paint.pathEffect = null
        c.drawPath(house, paint)
        // The lit window (a rounded square in the gradient's own colour, glowing) and a door.
        paint.color = Color.argb(255, Color.red(bottom), Color.green(bottom), Color.blue(bottom))
        val win = RectF(cx - s * 0.17f, cy + s * 0.02f, cx + s * 0.17f, cy + s * 0.36f)
        c.drawRoundRect(win, s * 0.06f, s * 0.06f, paint)
        paint.color = glow
        c.drawRoundRect(RectF(win.left + s * 0.04f, win.top + s * 0.04f, win.right - s * 0.04f, win.bottom - s * 0.04f), s * 0.04f, s * 0.04f, paint)
        // Three light rays above the roof's peak: the "smart" in the home.
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = s * 0.06f
        for (a in listOf(-38f, 0f, 38f)) {
            val rad = Math.toRadians((a - 90.0))
            val r0 = s * 0.12f; val r1 = s * 0.26f
            val ox = cx; val oy = roofTop - s * 0.02f
            c.drawLine(ox + (Math.cos(rad) * r0).toFloat(), oy + (Math.sin(rad) * r0).toFloat(),
                ox + (Math.cos(rad) * r1).toFloat(), oy + (Math.sin(rad) * r1).toFloat(), paint)
        }
    }
}
