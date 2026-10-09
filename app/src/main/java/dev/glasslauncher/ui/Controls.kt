package dev.glasslauncher.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import dev.glasslauncher.glass.GlassStyle
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.glass

@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = Shapes.panel,
    overlay: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val style = if (overlay) GlassStyle.overlay(palette.light) else GlassStyle.panel(palette.light)
    Box(modifier.glass(LocalBackdrop.current, shape, style), content = content)
}

/** A focusable row in the tvOS menu style: plain when idle, solid highlight with inverted text when focused. */
@Composable
fun MenuRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    chevron: Boolean = false,
    /** Red text (Uninstall, Delete): a warning in both states, as in tvOS menus. */
    destructive: Boolean = false,
    /** An on/off row: drawn as a switch and reported as one to accessibility (checked state). */
    checked: Boolean? = null,
    /** One short line about this row, shown in the Settings page's left column while it has focus. */
    help: String? = null,
) {
    val palette = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    // Only a press that started on this row counts, so the key-up from the press that opened a menu is ignored.
    val pressed = remember { booleanArrayOf(false) }
    val prefs = LocalUiPrefs.current
    val view = LocalView.current
    val sink = dev.glasslauncher.home.LocalTitleSink.current
    if (sink != null && focused) {
        val page = dev.glasslauncher.home.LocalPageKey.current
        androidx.compose.runtime.LaunchedEffect(help, page) { sink.help = help?.let { page to it } }
    }
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, if (focused) Motion.focusIn() else Motion.focusOut(), label = "rowScale")
    // SYS-02: rows rest on faint glass; the focused row is a solid white capsule. tvOS: the new row is
    // white within a frame (the capsule doesn't slide), the old one fades over ~60 ms.
    val bg by animateColorAsState(
        if (focused) palette.focusFill else palette.rowFill,
        if (focused) Motion.selectIn() else Motion.selectOut(),
        label = "rowBg",
    )
    val fg = when {
        destructive -> palette.danger
        focused -> palette.onFocusFill
        enabled -> palette.primary
        else -> palette.faint
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(bg, Shapes.pill)
            // In light appearance the white focus capsule needs an edge to stand out from the bright page.
            .then(if (focused && palette.light) Modifier.border(1.5.dp, palette.focusRing, Shapes.pill) else Modifier)
            .onFocusChanged {
                if (it.isFocused && !focused && prefs.sounds) view.playSoundEffect(navigationSound())
                focused = it.isFocused
                if (!it.isFocused) pressed[0] = false
            }
            .onKeyEvent { e ->
                val k = e.nativeKeyEvent
                val select = k.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || k.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    k.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                if (select) {
                    if (k.action == AndroidKeyEvent.ACTION_DOWN && k.repeatCount == 0) pressed[0] = true
                    if (k.action == AndroidKeyEvent.ACTION_UP) {
                        if (pressed[0] && enabled) {
                            if (prefs.sounds) view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                            onClick()
                        }
                        pressed[0] = false
                    }
                }
                select
            }
            .semantics {
                contentDescription = if (value != null) "$title, $value" else title
                if (checked != null) {
                    role = Role.Switch
                    toggleableState = androidx.compose.ui.state.ToggleableState(checked)
                } else role = Role.Button
                if (!enabled) disabled()
                onClick { if (enabled) onClick(); enabled }
            }
            .focusable()
            .padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            leading?.invoke()
            Text(title, style = Type.body, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (value != null) Text(value, style = Type.secondary, color = if (focused) fg.copy(alpha = 0.7f) else palette.secondary, maxLines = 1)
            trailing?.invoke(this)
            if (checked != null) Switch(checked, focused)
            // A tight line height: in the heading style the chevron set the row's height (48 dp, not tvOS's ~35).
            if (chevron) Text("›", style = Type.heading.copy(lineHeight = 18.sp), color = if (focused) fg.copy(alpha = 0.6f) else palette.faint)
        }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, help: String? = null) {
    MenuRow(title, onClick = { onChange(!checked) }, modifier = modifier, checked = checked, enabled = enabled, help = help)
}

/** A tvOS-style switch: a capsule track with a knob, green when on. Drawn in one Canvas (no animation cost at rest). */
@Composable
fun Switch(checked: Boolean, focused: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val t by animateFloatAsState(if (checked) 1f else 0f, Motion.focusOut(), label = "switch")
    androidx.compose.foundation.Canvas(modifier.size(46.dp, 28.dp)) {
        val off = if (focused) Color(0x330E1015) else palette.primary.copy(alpha = 0.22f)
        val on = Color(0xFF30D158)
        val track = androidx.compose.ui.graphics.lerp(off, on, t)
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        val r = size.height / 2 - 2.5.dp.toPx()
        val x = size.height / 2 + (size.width - size.height) * t
        drawCircle(Color.Black.copy(alpha = 0.18f), r, androidx.compose.ui.geometry.Offset(x, size.height / 2 + 1.dp.toPx()))
        drawCircle(Color.White, r, androidx.compose.ui.geometry.Offset(x, size.height / 2))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Type.overline,
        color = LocalPalette.current.secondary,
        modifier = modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    // In a full Settings page the words go to its left column (tvOS); the right side keeps only rows.
    val sink = dev.glasslauncher.home.LocalTitleSink.current
    if (sink != null) {
        val page = dev.glasslauncher.home.LocalPageKey.current
        androidx.compose.runtime.DisposableEffect(sink, page, text) {
            val entry = page to text
            sink.captions.add(entry)
            onDispose { sink.captions.remove(entry) }
        }
        return
    }
    Text(text, style = Type.secondary, color = LocalPalette.current.secondary, modifier = modifier.padding(horizontal = 18.dp, vertical = 6.dp))
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, CircleShape))
}

@Composable
fun Gap(width: Int) = Spacer(Modifier.width(width.dp))

@Composable
fun PillText(text: String, style: TextStyle = Type.caption) {
    Text(text, style = style, color = LocalPalette.current.primary, maxLines = 1)
}
