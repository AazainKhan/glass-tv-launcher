package dev.glasslauncher.glass

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * The flat colour a glass surface settles to: the scene behind it (averaged), under the glass's tint, then
 * the scene's dim. Control Center draws this while its tiles move and fades the real texture in once they
 * land (sampling it through the motion cost a third of the frames), and because the two match, the swap
 * shows only the texture's detail arriving, never a change of colour (plan §11).
 */
object GlassMatch {
    fun fill(average: Color, tint: Color, dim: Float): Color {
        val tinted = tint.compositeOver(average.copy(alpha = 1f))
        return Color.Black.copy(alpha = dim.coerceIn(0f, 1f)).compositeOver(tinted)
    }

    /** Mean colour of [source] in the rectangle [left]..[left]+[width] × [top]..[top]+[height] (source pixels), from a 7×7 sample. */
    fun average(source: Bitmap, left: Float, top: Float, width: Float, height: Float): Color {
        var r = 0f; var g = 0f; var b = 0f; var n = 0
        for (j in 0 until 7) for (i in 0 until 7) {
            val x = (left + width * (i + 0.5f) / 7f).toInt().coerceIn(0, source.width - 1)
            val y = (top + height * (j + 0.5f) / 7f).toInt().coerceIn(0, source.height - 1)
            val p = source.getPixel(x, y)
            r += android.graphics.Color.red(p); g += android.graphics.Color.green(p); b += android.graphics.Color.blue(p); n++
        }
        return Color(r / n / 255f, g / n / 255f, b / n / 255f)
    }
}
