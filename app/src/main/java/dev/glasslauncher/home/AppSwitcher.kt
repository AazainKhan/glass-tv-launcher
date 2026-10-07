package dev.glasslauncher.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.glass.GlassStyle
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.glass
import dev.glasslauncher.system.RecentApps
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Type

/**
 * The app switcher (the remote's ⧉ button, or Control Center): recently used apps as large cards over
 * the blurred Home, newest first, like tvOS. Select opens an app, Up closes it, Back dismisses.
 * Cards show each app's tile art: other apps' screenshots need system privileges.
 */
@Composable
fun AppSwitcher(model: HomeModel, layout: HomeLayout, cfg: LauncherConfig, active: Boolean, closeAll: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val apps = remember { mutableStateListOf<dev.glasslauncher.apps.AppEntry>() }
    LaunchedEffect(layout.loaded) {
        apps.clear()
        apps += RecentApps.list(context, layout.installed, cfg.recentApps)
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(active, apps.isEmpty()) {
        if (active && apps.isNotEmpty()) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
    FullOverlay(active) {
        Box(Modifier.fillMaxSize().glass(LocalBackdrop.current, RectangleShape, GlassStyle.overlay(palette.light).copy(highlight = 0f, rim = 0f)))
        if (apps.isEmpty()) {
            Text(
                "Apps you open will appear here.",
                style = Type.body,
                color = palette.secondary,
                modifier = Modifier.align(Alignment.Center),
            )
            return@FullOverlay
        }
        Column(Modifier.align(Alignment.Center).fillMaxWidth()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                contentPadding = PaddingValues(horizontal = ((960 - CARD_W) / 2).dp),
                modifier = Modifier.fillMaxWidth().testTag("app-switcher"),
            ) {
                items(apps, key = { it.packageName }) { app ->
                    val art = rememberArt(model, app)
                    val view = androidx.compose.ui.platform.LocalView.current
                    var cardBounds by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            app.label,
                            style = Type.label,
                            color = palette.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 14.dp),
                        )
                        FocusTile(
                            label = app.label,
                            onClick = { closeAll(); model.launch(app, view, cardBounds) },
                            shape = RoundedCornerShape(18.dp),
                            focusedScale = 1.08f,
                            modifier = Modifier
                                .size(CARD_W.dp, (CARD_W * 3 / 5).dp)
                                .then(androidx.compose.ui.Modifier.onGloballyPositioned { cardBounds = it.boundsInWindow() })
                                .then(if (app == apps.first()) Modifier.focusRequester(first) else Modifier)
                                .onPreviewKeyEvent { e ->
                                    val k = e.nativeKeyEvent
                                    // Up throws the app away, as swiping up does on tvOS.
                                    if (k.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP && k.action == AndroidKeyEvent.ACTION_DOWN) {
                                        RecentApps.close(context, app.packageName)
                                        model.edit { c -> c.copy(recentApps = c.recentApps - app.packageName) }
                                        apps.remove(app)
                                        true
                                    } else false
                                },
                        ) {
                            art?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
            Text(
                "Press up to close an app",
                style = Type.caption,
                color = palette.secondary,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 34.dp),
            )
        }
    }
}

private const val CARD_W = 300
