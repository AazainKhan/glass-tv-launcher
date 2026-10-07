package dev.glasslauncher.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
) {
    val palette = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    // Only a press that started on this row counts, so the key-up from the press that opened a menu is ignored.
    val pressed = remember { booleanArrayOf(false) }
    val prefs = LocalUiPrefs.current
    val view = LocalView.current
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, spring(0.7f, 500f), label = "rowScale")
    val bg by animateColorAsState(if (focused) palette.focusFill else Color.Transparent, label = "rowBg")
    val fg = when {
        focused -> palette.onFocusFill
        enabled -> palette.primary
        else -> palette.faint
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(bg, Shapes.row)
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
                role = Role.Button
                onClick { if (enabled) onClick(); enabled }
            }
            .focusable()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            leading?.invoke()
            Text(title, style = Type.body, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (value != null) Text(value, style = Type.secondary, color = if (focused) fg.copy(alpha = 0.7f) else palette.secondary, maxLines = 1)
            trailing?.invoke(this)
        }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    MenuRow(title, onClick = { onChange(!checked) }, modifier = modifier, value = if (checked) "On" else "Off")
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
