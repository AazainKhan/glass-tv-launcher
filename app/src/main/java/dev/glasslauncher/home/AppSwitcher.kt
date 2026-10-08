package dev.glasslauncher.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Text
import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.system.RecentApps
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Type

/**
 * The app switcher (the remote's ⧉ button, or Control Center), laid out like tvOS: the newest app's card
 * large in the centre with its icon and name above it, earlier apps stacked and overlapping to its left,
 * and Home peeking in on the right. Left and Right move through the stack, Select opens, Up closes an app,
 * Back dismisses. No live previews: cards show the app's tile art, Home's card the snapshot already taken
 * for the backdrop. Only transforms animate, so moving costs no recomposition of the cards' content.
 */
@Composable
fun AppSwitcher(model: HomeModel, layout: HomeLayout, cfg: LauncherConfig, active: Boolean, closeAll: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val backdrop = LocalBackdrop.current
    // Home's own baked art (no screen capture): the sharp slide for Home's card, the blurred one behind.
    val homeShot = remember { backdrop.backdrop?.sharp }
    // Last time's list straight away (so the cards are laid out on the first frame), refreshed below.
    val apps = remember { mutableStateListOf<AppEntry>().apply { addAll(lastApps) } }
    var selected by remember { mutableIntStateOf(lastApps.lastIndex) }
    LaunchedEffect(layout.loaded) {
        // Reading usage events covers days of history: off the main thread, or opening stutters.
        val recent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            RecentApps.list(context, layout.installed, cfg.recentApps).reversed() // oldest … newest
        }
        lastApps = recent
        if (recent != apps.toList()) {
            apps.clear()
            apps += recent
            selected = apps.lastIndex
        }
    }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    fun idAt(i: Int) = if (i == apps.size) HOME_ID else apps[i].packageName
    LaunchedEffect(active, selected, apps.size) {
        if (active && selected >= 0) { withFrameNanos { }; runCatching { requester(idAt(selected)).requestFocus() } }
    }
    val enter = rememberOverlayEnter()
    Box(Modifier.fillMaxSize().trapFocus(active)) {
        // Behind the cards: Home's already-baked blurred wallpaper, dimmed. No screen capture (capturing
        // and blurring Home on every open made it ~47% janky), and once the switcher is fully up Home
        // itself isn't drawn, so the switcher is one full-screen image plus its cards.
        val blurred = backdrop.backdrop?.blurred
        // The background is there from the first frame (tvOS overlays pop in): Home stops drawing at once,
        // so opening and closing draw one full-screen image plus the cards, never Home underneath too.
        val leaving = LocalOverlayExiting.current
        androidx.compose.runtime.DisposableEffect(blurred, leaving) {
            backdrop.homeHidden = blurred != null && !leaving
            onDispose { backdrop.homeHidden = false }
        }
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val e = if (leaving) enter.value else 1f
            if (blurred != null) drawImage(blurred, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()), alpha = e)
            drawRect(Color.Black.copy(alpha = 0.32f * e))
        }
        if (apps.isEmpty()) {
            Text(
                "Apps you open will appear here.",
                style = Type.body,
                color = palette.secondary,
                modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = enter.value },
            )
            return@Box
        }
        val view = LocalView.current
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    alpha = enter.value
                    val s = 0.94f + 0.06f * enter.value
                    scaleX = s; scaleY = s
                    // Fading the stack through an offscreen buffer cost a full-screen pass per frame.
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                }
                .testTag("app-switcher")
                .onPreviewKeyEvent { e ->
                    val k = e.nativeKeyEvent
                    if (k.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (k.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (selected > 0) selected--; true }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (selected < apps.size) selected++; true }
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> true
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            // Up throws the app away, as swiping up does on tvOS.
                            apps.getOrNull(selected)?.let { app ->
                                RecentApps.close(context, app.packageName)
                                model.edit { c -> c.copy(recentApps = c.recentApps - app.packageName) }
                                apps.removeAt(selected)
                                selected = selected.coerceAtMost(apps.size)
                            }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            // The focused app's icon and name, above the centre card's left edge.
            val title = apps.getOrNull(selected)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.TopStart).offset(x = (CENTRE_X - CARD_W / 2).dp, y = (CARD_TOP - 52).dp),
            ) {
                if (title != null) {
                    rememberArt(model, title)?.let {
                        Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp, 34.dp).clip(RoundedCornerShape(7.dp)))
                    }
                }
                Text(
                    title?.label ?: "Home",
                    style = Type.heading,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp).testTag("switcher-title"),
                )
            }
            for (i in 0..apps.size) {
                val d = i - selected
                if (d < -STACKED - 1 || d > 2) continue
                val id = idAt(i)
                val app = apps.getOrNull(i)
                val p by animateFloatAsState(d.toFloat(), androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 520f), label = "card")
                var bounds by remember { mutableStateOf<Rect?>(null) }
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (CENTRE_X - CARD_W / 2).dp, y = CARD_TOP.dp)
                        .zIndex(if (d <= 0) 10f + d else 10f - d)
                        .graphicsLayer {
                            val k = (-p).coerceAtLeast(0f)
                            val s = 1f - 0.08f * k
                            scaleX = s; scaleY = s
                            translationX = (if (p >= 0f) p * (CARD_W + CARD_GAP) else -k * STACK_STEP).dp.toPx()
                            alpha = (STACKED + 1 - k).coerceIn(0f, 1f)
                        }
                        .onGloballyPositioned { bounds = it.boundsInWindow() },
                ) {
                    FocusTile(
                        label = app?.label ?: "Home",
                        onClick = {
                            if (app == null) closeAll()
                            else { closeAll(); model.launch(app, view, bounds) }
                        },
                        shape = RoundedCornerShape(22.dp),
                        focusedScale = 1f,
                        // Soft shadows under cards this size were most of the switcher's GPU time.
                        shadow = false,
                        modifier = Modifier
                            .size(CARD_W.dp, CARD_H.dp)
                            .focusRequester(requester(id))
                            .testTag("switcher-card:$id"),
                    ) {
                        if (app != null) {
                            AppCard(model, app)
                        } else if (homeShot != null) {
                            Image(homeShot, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Box(Modifier.fillMaxSize().background(Color(0xFF1C1E24)))
                        }
                        // Cards further back dim, so the stack reads as depth.
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = ((-p).coerceIn(0f, 3f) * 0.18f) }.background(Color.Black))
                    }
                }
            }
            Text(
                "Press up to close an app",
                style = Type.caption,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp),
            )
        }
    }
}

/**
 * An app's card: its blurred preview (AppPreviews), or until there is one, a blurred wash of its art with
 * the icon at a modest size (blown up to card size, banners look soft).
 */
@Composable
private fun AppCard(model: HomeModel, app: AppEntry) {
    val context = LocalContext.current
    val preview by androidx.compose.runtime.produceState(
        dev.glasslauncher.system.AppPreviews.cached(context, app.packageName)?.asImageBitmap(), app.packageName,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            dev.glasslauncher.system.AppPreviews.load(context, app.packageName)?.asImageBitmap()
        }
    }
    val art = rememberArt(model, app)
    val p = preview
    if (p != null) {
        Image(p, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag("switcher-preview:${app.packageName}"))
        return
    }
    if (art == null) { Box(Modifier.fillMaxSize().background(Color(0xFF1C1E24))); return }
    val wash by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, art) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val a = art.asAndroidBitmap()
                dev.glasslauncher.widgets.backdropArt(if (a.config == android.graphics.Bitmap.Config.HARDWARE) a.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else a).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF1C1E24)), contentAlignment = Alignment.Center) {
        wash?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.size(150.dp, 90.dp).clip(RoundedCornerShape(14.dp)))
    }
}

private const val HOME_ID = "home"

/** The switcher's list from last time, shown at once next time while the fresh one loads. */
private var lastApps: List<AppEntry> = emptyList()
private const val CENTRE_X = 480
private const val CARD_W = 500
private const val CARD_H = CARD_W * 9 / 16
private const val CARD_TOP = (540 - CARD_H) / 2 + 10
private const val CARD_GAP = 44
private const val STACK_STEP = 92
private const val STACKED = 3
