package dev.glasslauncher.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glasslauncher.R
import dev.glasslauncher.data.Auto
import dev.glasslauncher.data.LauncherConfig

@Immutable
data class Palette(val light: Boolean, val highContrast: Boolean = false) {
    val primary = if (light) Color(0xFF0E1015) else Color.White
    // Secondary and faint text keep WCAG AA (4.5:1) on the glass and grid ranges set by Blur.legible.
    val secondary = if (light) Color(if (highContrast) 0xF00E1015 else 0xCC0E1015) else Color(if (highContrast) 0xF0FFFFFF else 0xDBFFFFFF)
    val faint = if (light) Color(0x990E1015) else Color(0x99FFFFFF)
    val focusFill = if (light) Color(0xFF0E1015) else Color.White
    val onFocusFill = if (light) Color.White else Color(0xFF0E1015)
    val scrim = if (light) Color(0x33FFFFFF) else Color(0x66000000)
    val accent = Color(0xFF5AC8FA)
}

val LocalPalette = staticCompositionLocalOf { Palette(light = false) }

/**
 * Comfort and accessibility switches resolved from the user's settings and the system's.
 * Reduce motion removes tilt, wiggle and movement (dissolves stay); reduce transparency makes glass
 * nearly opaque (also the cheapest rendering path).
 */
@Immutable
data class UiPrefs(
    val reduceMotion: Boolean = false,
    val reduceTransparency: Boolean = false,
    val highContrast: Boolean = false,
    val sounds: Boolean = true,
) {
    companion object {
        fun resolve(context: Context, cfg: LauncherConfig): UiPrefs {
            val cr = context.contentResolver
            val systemNoAnimations = runCatching { Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE) }.getOrDefault(1f) == 0f
            val systemHighContrast = runCatching { Settings.Secure.getInt(cr, "high_text_contrast_enabled") }.getOrDefault(0) == 1
            return UiPrefs(
                reduceMotion = when (cfg.reduceMotion) {
                    Auto.Auto -> systemNoAnimations
                    Auto.On -> true
                    Auto.Off -> false
                },
                reduceTransparency = cfg.reduceTransparency || systemHighContrast,
                highContrast = systemHighContrast,
                sounds = cfg.sounds,
            )
        }
    }
}

val LocalUiPrefs = staticCompositionLocalOf { UiPrefs() }

object Shapes {
    val tile = RoundedCornerShape(12.dp)
    val panel = RoundedCornerShape(30.dp)
    val pill = RoundedCornerShape(50)
    val row = RoundedCornerShape(16.dp)
}

/** Inter: an open-licence face close to SF Pro. Display cut for large titles. */
val InterFamily = FontFamily(
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)
val InterDisplay = FontFamily(Font(R.font.inter_display_bold, FontWeight.Bold))

/**
 * 10-foot type scale. The UI renders at 960x540dp on a 1080p panel, so 1sp = 2px:
 * body 16sp = 32px (tvOS minimum 29pt), secondary 14.5sp = 29px, titles >= 56px.
 */
object Type {
    val display = TextStyle(fontFamily = InterDisplay, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, lineHeight = 42.sp)
    val title = TextStyle(fontFamily = InterDisplay, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val heading = TextStyle(fontFamily = InterFamily, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val secondary = TextStyle(fontFamily = InterFamily, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp)
    val caption = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp)
    val label = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
    val overline = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

    val shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), 10f)
}

/** TV-safe insets: tvOS keeps critical content 60pt (30dp here) from every edge. */
object Safe {
    val horizontal = 48.dp
    val top = 32.dp
    val bottom = 30.dp
}

/** Direction of the most recent D-pad press; lets newly focused tiles tilt in from that side. */
object KeyDirection {
    var dx = 0
    var dy = 0
}
