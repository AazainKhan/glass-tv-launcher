package dev.glasslauncher.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.glasslauncher.R
import dev.glasslauncher.app
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.launch
import dev.glasslauncher.addons.AddonSurfaces
import dev.glasslauncher.addons.HomeRowShown
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalMetrics
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.ReliableImage
import dev.glasslauncher.ui.Type

/**
 * An add-on's Home row, drawn as a normal row of cards under the apps. The title ends "· via <add-on>" and a small puzzle
 * piece marks it as add-on content. Glass fetches each card's art itself, at the size shown; Select goes through Glass,
 * which builds the Intent from the validated link, component or launcher entry.
 */
@Composable
internal fun AddonRow(shown: HomeRowShown, onFocused: () -> Unit) {
    val m = LocalMetrics.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val visible = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp).onFocusChanged {
            if (it.hasFocus) {
                onFocused()
                // Focus on the row is a reason to refresh it (its interval permitting): off Main, never blocking focus.
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val c = context.app.config.config.value
                    AddonSurfaces.refreshRowOnFocus(context, dev.glasslauncher.addons.AddonSnapshot(c.addonsEnabled, c.addonSettings), shown.pkg, visible)
                }
            }
        }.stopAtRowEnds().testTag("addon-row"),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.padding(start = m.inset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painterResource(R.drawable.ic_widgets), null, colorFilter = ColorFilter.tint(palette.faint), modifier = Modifier.size(16.dp))
            BasicText(shown.row.title, style = Type.heading.copy(color = palette.primary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(m.gutter), contentPadding = PaddingValues(horizontal = m.inset)) {
            items(shown.row.items, key = { it.id }) { card ->
                Column(Modifier.width(CARD_W), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusTile(
                        label = card.title,
                        onClick = { card.action?.let { AddonSurfaces.open(context, shown.pkg, it) } },
                        shape = RoundedCornerShape(14.dp),
                        edgeLight = true,
                        modifier = Modifier.size(CARD_W, CARD_H),
                    ) { focused ->
                        ReliableImage(card.image, Modifier.size(CARD_W, CARD_H), title = card.title, subtitle = card.subtitle, width = 480, height = 270, focused = focused)
                    }
                    BasicText(card.title, style = Type.caption.copy(color = palette.primary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private val CARD_W = 176.dp
private val CARD_H = 99.dp

