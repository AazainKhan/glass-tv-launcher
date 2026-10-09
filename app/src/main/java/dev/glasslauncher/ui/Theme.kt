package dev.glasslauncher.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    // tvOS: focus is a white capsule in both appearances (a near-black one in light made buttons flip
    // between dark and light as focus moved across them).
    val focusFill = Color.White
    val onFocusFill = Color(0xFF0E1015)
    val scrim = if (light) Color(0x33FFFFFF) else Color(0x66000000)
    /** The new-app dot and other small accents: deeper in light so it keeps 3:1 on the pastel page. */
    val accent = if (light) Color(0xFF0A72B8) else Color(0xFF5AC8FA)
    /** Destructive text (Uninstall, Delete): a darker red in light so it reads on white and on the page. */
    val danger = if (light) Color(0xFFC4281C) else Color(0xFFFF453A)
    /** Rows at rest: lighter than the light page (white glass), faint white on dark. */
    val rowFill = if (light) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f)
    /** A soft ring around the white focus capsule in light, so focus stands out from a bright page (≥3:1). */
    val focusRing = if (light) Color(0x800E1015) else Color.Transparent
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
                highContrast = systemHighContrast || cfg.increaseContrast,
                sounds = cfg.sounds,
            )
        }
    }
}

val LocalUiPrefs = staticCompositionLocalOf { UiPrefs() }

object Shapes {
    /** The default for a [FocusTile]: the same corner as a Home tile at the standard text size. */
    val tile = RoundedCornerShape(Metrics().tileRadius)
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
/** Bold Text: the same weights mapped one step heavier (Medium draws SemiBold, SemiBold draws Bold). */
val InterBold = FontFamily(
    Font(R.font.inter_semibold, FontWeight.Medium),
    Font(R.font.inter_display_bold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold),
)

/** The TV's own faces, for Settings › Display & Text › Font. */
private val CondensedFamily by lazy { FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)) }

/**
 * 10-foot type scale. The UI renders at 960x540dp on a 1080p panel, so 1sp = 2px:
 * body 16sp = 32px (tvOS minimum 29pt), secondary 14.5sp = 29px, titles >= 56px.
 */
object Type {
    /** Settings › Display & Text › Bold Text: every style one weight heavier. Snapshot state, so text recomposes. */
    var bold by androidx.compose.runtime.mutableStateOf(false)
    /** Settings › Display & Text › Font. Snapshot state, so text recomposes. */
    var font by androidx.compose.runtime.mutableStateOf(dev.glasslauncher.data.UiFont.Inter)
    val family: FontFamily get() = when (font) {
        dev.glasslauncher.data.UiFont.Inter -> if (bold) InterBold else InterFamily
        dev.glasslauncher.data.UiFont.System -> FontFamily.SansSerif
        dev.glasslauncher.data.UiFont.Condensed -> CondensedFamily
        dev.glasslauncher.data.UiFont.Serif -> FontFamily.Serif
    }
    /** Bold Text for the TV's own faces: one step heavier (Inter does it through its [InterBold] family instead). */
    private fun w(base: FontWeight): FontWeight =
        if (bold && font != dev.glasslauncher.data.UiFont.Inter) FontWeight((base.weight + 100).coerceAtMost(900)) else base
    private val displayFamily: FontFamily get() = if (font == dev.glasslauncher.data.UiFont.Inter) InterDisplay else family

    // Sized to tvOS 27's measured type at 1080p (body ~28 px, secondary ~25 px): smaller than before.
    val display get() = TextStyle(fontFamily = displayFamily, fontSize = 34.sp, fontWeight = w(FontWeight.Bold), letterSpacing = (-0.6).sp, lineHeight = 38.sp)
    val title get() = TextStyle(fontFamily = displayFamily, fontSize = 24.5.sp, fontWeight = w(FontWeight.Bold), letterSpacing = (-0.4).sp)
    val heading get() = TextStyle(fontFamily = family, fontSize = 17.5.sp, fontWeight = w(FontWeight.SemiBold))
    val body get() = TextStyle(fontFamily = family, fontSize = 14.sp, fontWeight = w(FontWeight.Medium))
    val secondary get() = TextStyle(fontFamily = family, fontSize = 13.5.sp, fontWeight = w(FontWeight.Medium), lineHeight = 18.sp)
    // 13 sp is the floor: smaller text isn't readable from a sofa (audit 9.3).
    val caption get() = TextStyle(fontFamily = family, fontSize = 13.sp, fontWeight = w(FontWeight.Medium), lineHeight = 17.sp)
    val label get() = TextStyle(fontFamily = family, fontSize = 13.5.sp, fontWeight = w(FontWeight.SemiBold), letterSpacing = 0.1.sp)
    val overline get() = TextStyle(fontFamily = family, fontSize = 13.sp, fontWeight = w(FontWeight.SemiBold), letterSpacing = 1.2.sp)


    /** For white text straight on busy art: tighter and darker, so the letters keep an edge. */

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
