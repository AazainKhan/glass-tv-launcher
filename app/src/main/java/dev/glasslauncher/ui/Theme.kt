package dev.glasslauncher.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class Palette(val light: Boolean) {
    val primary = if (light) Color(0xFF111318) else Color.White
    val secondary = if (light) Color(0xB3111318) else Color(0xB3FFFFFF)
    val faint = if (light) Color(0x66111318) else Color(0x66FFFFFF)
    val focusFill = if (light) Color(0xFF111318) else Color.White
    val onFocusFill = if (light) Color.White else Color(0xFF111318)
    val scrim = if (light) Color(0x33FFFFFF) else Color(0x66000000)
}

val LocalPalette = staticCompositionLocalOf { Palette(light = false) }

object Shapes {
    val tile = RoundedCornerShape(12.dp)
    val panel = RoundedCornerShape(28.dp)
    val pill = RoundedCornerShape(50)
    val row = RoundedCornerShape(14.dp)
}

object Type {
    val title = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val heading = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
}

/** Direction of the most recent D-pad press; lets newly focused tiles tilt in from that side. */
object KeyDirection {
    var dx = 0
    var dy = 0
}
