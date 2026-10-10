package dev.glasslauncher.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glasslauncher.app
import dev.glasslauncher.data.Wallpaper
import dev.glasslauncher.data.WallpaperKind
import dev.glasslauncher.glass.BackdropState
import dev.glasslauncher.glass.GlassStyle
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.glass
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.GlassBox
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.MenuRow
import dev.glasslauncher.ui.Palette
import dev.glasslauncher.ui.Shapes
import kotlinx.coroutines.runBlocking

/*
 * Component previews, rendered by Roborazzi's preview scanner as screenshot tests
 * (`scripts/shots verify previews`, baselines in app/src/test/screenshots/previews/). Each one sits on
 * the real wallpaper so glass samples what it would on Home. Debug-only: nothing here ships.
 *
 * Add a preview here when you work on a component: it renders in a couple of seconds without
 * driving Home, so it's the fastest place to iterate on blur, edges, focus scale and spacing.
 */

/** The real preset wallpaper as the glass backdrop, with the matching palette. */
@Composable
fun PreviewStage(light: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val state = remember(light) {
        BackdropState().also { s ->
            runBlocking {
                s.swap(context.app.wallpapers.load(Wallpaper(WallpaperKind.Preset, if (light) "dawn" else "aurora"), light = light), animate = false)
            }
        }
    }
    CompositionLocalProvider(LocalBackdrop provides state, LocalPalette provides Palette(light = light)) {
        Box(Modifier.fillMaxSize().onSizeChanged { state.rootSize = it }) {
            state.backdrop?.let { Image(it.sharp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            content()
        }
    }
}

@Composable
private fun FakeAppArt(name: String, color: Color, ink: Color = Color.White) {
    Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
        Text(name, color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** Three tiles, the first focused: scale, shadow and label as on Home. */
@Composable
private fun TileRow() {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Row(Modifier.padding(40.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        listOf(Triple("Netflix", Color(0xFF141414), Color(0xFFE50914)), Triple("YouTube", Color.White, Color(0xFFFF0033)),
            Triple("Plex", Color(0xFF1F1F1F), Color(0xFFE5A00D))).forEachIndexed { i, (name, bg, ink) ->
            FocusTile(
                label = name,
                onClick = {},
                edgeLight = true,
                // Home's tiles glow in their art's colour: saturated for these two, none under the white tile.
                glowColor = ink.takeIf { bg != Color.White },
                modifier = Modifier.width(150.dp).aspectRatio(5f / 3f).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
            ) { FakeAppArt(name, bg, ink) }
        }
    }
}

@Preview(name = "tiles-dark", widthDp = 640, heightDp = 220)
@Composable
fun TilesDarkPreview() = PreviewStage { TileRow() }

@Preview(name = "tiles-light", widthDp = 640, heightDp = 220)
@Composable
fun TilesLightPreview() = PreviewStage(light = true) { TileRow() }

/** The dock tray: clear glass with the edge band, over the wallpaper. */
@Composable
private fun Tray(light: Boolean) {
    Box(
        Modifier.padding(24.dp).fillMaxWidth()
            .glass(LocalBackdrop.current, Shapes.panel, GlassStyle.shelf(light))
            .padding(vertical = 20.dp, horizontal = 24.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf("Netflix" to Color(0xFF141414), "YouTube" to Color.White, "Stremio" to Color(0xFF1B1340)).forEach { (n, c) ->
                Box(Modifier.width(140.dp).aspectRatio(5f / 3f).background(c, Shapes.tile))
            }
        }
    }
}

@Preview(name = "tray-dark", widthDp = 640, heightDp = 200)
@Composable
fun TrayDarkPreview() = PreviewStage { Tray(light = false) }

@Preview(name = "tray-light", widthDp = 640, heightDp = 200)
@Composable
fun TrayLightPreview() = PreviewStage(light = true) { Tray(light = true) }

/** A menu panel: overlay glass with rows, the first focused. */
@Composable
private fun MenuPanel() {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    GlassBox(Modifier.padding(24.dp).width(360.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MenuRow("Open", {}, Modifier.focusRequester(first))
            MenuRow("Move", {})
            MenuRow("Change Icon…", {}, value = "Default")
            MenuRow("Hide", {})
        }
    }
}

@Preview(name = "menu-dark", widthDp = 420, heightDp = 300)
@Composable
fun MenuDarkPreview() = PreviewStage { MenuPanel() }

@Preview(name = "menu-light", widthDp = 420, heightDp = 300)
@Composable
fun MenuLightPreview() = PreviewStage(light = true) { MenuPanel() }

/** The Cover Flow (Spotify's Top Shelf) with synthetic square covers: centre flat, neighbours turned and stacked, reflections baked. */
@Preview(widthDp = 960, heightDp = 540, name = "cover-flow")
@Composable
fun CoverFlowPreview() {
    val items = remember { (0 until 9).map { dev.glasslauncher.featured.FeaturedItem(id = "c$it", title = "Album ${it + 1}", subtitle = "Artist ${it + 1}", image = "cover$it", aspect = 1f) } }
    val art = remember {
        items.mapIndexed { i, item ->
            val hue = i * 40f
            val bmp = android.graphics.Bitmap.createBitmap(280, 280, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.drawColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.55f)))
            c.drawCircle(140f, 140f, 80f, android.graphics.Paint().apply { color = android.graphics.Color.HSVToColor(floatArrayOf((hue + 30f) % 360f, 0.7f, 0.9f)) })
            item.image to dev.glasslauncher.featured.CoverReflection.bake(bmp).asImageBitmap()
        }.toMap()
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF16181F))) {
        dev.glasslauncher.featured.CoverFlow(
            items = items, index = 4, onIndex = {}, onOpen = {}, onExit = {}, label = "Spotify",
            focusRequester = remember { FocusRequester() },
            coverArt = { url -> art[url] },
        )
    }
}
