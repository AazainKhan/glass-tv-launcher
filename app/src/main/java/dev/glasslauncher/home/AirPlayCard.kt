package dev.glasslauncher.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.MenuRow
import dev.glasslauncher.ui.Type

/** AirPlay's pairing code on Glass's own alert card. It goes when PhairPlay says so, when its time is up, or on Dismiss/Back. */
@Composable
internal fun AirPlayCodeCard(o: Overlay.AirPlayCode, active: Boolean, close: () -> Unit) {
    val palette = LocalPalette.current
    val first = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) { withFrameNanos { }; runCatching { first.requestFocus() } } }
    val closed by rememberUpdatedState(o.onClosed)
    DisposableEffect(Unit) { onDispose { closed() } }
    AlertCard(active) {
        Text("AirPlay Code", style = Type.heading, color = palette.primary)
        Text("Enter this code on the device that is connecting.", style = Type.secondary, color = palette.secondary)
        Text(
            o.code, style = Type.display.copy(letterSpacing = 6.sp), color = palette.primary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { testTag = "airplay-code" },
        )
        MenuRow("Dismiss", close, Modifier.focusRequester(first), centered = true)
    }
}
