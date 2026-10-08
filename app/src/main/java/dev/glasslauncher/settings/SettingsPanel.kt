package dev.glasslauncher.settings

import dev.glasslauncher.data.ScreensaverMode
import dev.glasslauncher.system.SystemControls
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.platform.testTag
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.glasslauncher.BuildConfig
import dev.glasslauncher.app
import dev.glasslauncher.data.AerialQuality
import dev.glasslauncher.data.Auto
import dev.glasslauncher.data.BackgroundMode
import dev.glasslauncher.system.PhoneField
import dev.glasslauncher.data.FeaturedMode
import dev.glasslauncher.data.FeaturedSourceId
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.ThemeMode
import dev.glasslauncher.data.Wallpaper
import dev.glasslauncher.data.WallpaperKind
import dev.glasslauncher.dream.AerialActivity
import dev.glasslauncher.dream.Screensaver
import dev.glasslauncher.featured.Plex
import dev.glasslauncher.featured.Sources
import dev.glasslauncher.glass.WallpaperLoader
import dev.glasslauncher.home.HomeLayout
import dev.glasslauncher.home.HomeModel
import dev.glasslauncher.home.MenuList
import dev.glasslauncher.home.Overlay
import dev.glasslauncher.home.PanelTitle
import dev.glasslauncher.system.Backup
import dev.glasslauncher.system.HomeSetup
import dev.glasslauncher.system.RemoteAction
import dev.glasslauncher.system.RemoteButtons
import dev.glasslauncher.system.Release
import dev.glasslauncher.system.Updater
import dev.glasslauncher.ui.Hint
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.MenuRow
import dev.glasslauncher.ui.SectionLabel
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.ToggleRow
import dev.glasslauncher.ui.Type
import dev.glasslauncher.ui.dissolve
import dev.glasslauncher.widgets.NowPlayingSource
import dev.glasslauncher.widgets.Weather
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.compose.ui.text.style.TextOverflow
import dev.glasslauncher.ui.FocusTile

private sealed interface Page {
    data object Root : Page
    data object Appearance : Page
    data class Wallpapers(val dark: Boolean) : Page
    data object Featured : Page
    data object PlexLink : Page
    data object Hidden : Page
    data object HideMore : Page
    data object IconPack : Page
    data object Screensaver : Page
    data object Widgets : Page
    data object HomeButton : Page
    data object Updates : Page
    data object Backup : Page
    data object About : Page
    data object Accessibility : Page
    data object RemoteButtons : Page
    data object RootTools : Page
    data object DisplayText : Page
    data object TextSize : Page
    data object ControlCenterTiles : Page
    data object Freezer : Page
    data object RootLog : Page
    data class ButtonAction(val id: String) : Page
    data class ButtonApp(val id: String) : Page
}

/** A selected row that removes itself takes focus with it; focus goes back to the page's first row. */
private fun refocus(first: androidx.compose.ui.focus.FocusRequester) {
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { first.requestFocus() } }, 50)
}

@Composable
fun SettingsPanel(
    model: HomeModel,
    cfg: LauncherConfig,
    layout: HomeLayout,
    active: Boolean,
    open: (Overlay) -> Unit,
    close: () -> Unit,
) {
    var stack by remember { mutableStateOf(listOf<Page>(Page.Root)) }
    fun push(p: Page) { stack = stack + p }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) else close() }
    BackHandler(enabled = active && stack.size > 1) { pop() }

    // tvOS page push: the new page slides in ~130 px from the right while fading in, the old one slides
    // left; reversed going back. Focus appears only once the page has landed.
    val reduceMotion = dev.glasslauncher.ui.LocalUiPrefs.current.reduceMotion
    androidx.compose.animation.AnimatedContent(
        targetState = stack,
        transitionSpec = {
            val forward = targetState.size >= initialState.size
            val dist = 130
            val ms = if (reduceMotion) 0 else dev.glasslauncher.ui.Motion.PAGE_MS
            val ease = androidx.compose.animation.core.LinearOutSlowInEasing
            (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(ms, easing = ease)) { if (forward) dist else -dist } +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(ms, easing = ease))) togetherWith
                (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(ms, easing = ease)) { if (forward) -dist else dist } +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(ms / 2)))
        },
        contentKey = { it.last() },
        label = "settings-page",
    ) { pages ->
        val page = pages.last()
        val landed = transition.currentState == transition.targetState
        val pageActive = active && landed && page == stack.last()
        if (page is Page.Wallpapers) {
            WallpaperPage(page.dark, model, cfg, pageActive, open)
        } else androidx.compose.runtime.CompositionLocalProvider(
            dev.glasslauncher.home.LocalPageKey provides page,
            dev.glasslauncher.home.LocalConfirm provides { c -> open(c) },
        ) { MenuList(pageActive) { first ->
            val f = Modifier.focusRequester(first)
            when (page) {
                Page.Root -> RootPage(model, cfg, f, ::push, open)
                Page.Appearance -> AppearancePage(model, cfg, f, ::push)
                Page.Featured -> FeaturedPage(model, cfg, f, open, ::push)
                Page.PlexLink -> PlexLinkPage(model, cfg, f, ::pop)
                Page.Hidden -> HiddenPage(model, cfg, layout, f, ::push) { refocus(first) }
                Page.HideMore -> HideMorePage(model, layout, f) { refocus(first) }
                Page.IconPack -> IconPackPage(model, cfg, f)
                Page.Screensaver -> ScreensaverPage(model, cfg, f)
                Page.Widgets -> WidgetsPage(model, cfg, f, open)
                Page.HomeButton -> HomeButtonPage(model, cfg, f)
                Page.Updates -> UpdatesPage(f)
                Page.Backup -> BackupPage(model, f)
                Page.About -> AboutPage(f)
                Page.Accessibility -> AccessibilityPage(model, cfg, f)
                Page.RemoteButtons -> RemoteButtonsPage(cfg, f, ::push)
                Page.RootTools -> RootToolsPage(f, ::push)
                Page.DisplayText -> DisplayTextPage(model, cfg, f, ::push)
                Page.TextSize -> TextSizePage(model, cfg, f)
                Page.ControlCenterTiles -> ControlCenterTilesPage(model, cfg, f)
                Page.Freezer -> FreezerPage(f)
                Page.RootLog -> RootLogPage(f)
                is Page.ButtonAction -> ButtonActionPage(model, cfg, page.id, f, ::push, ::pop)
                is Page.ButtonApp -> ButtonAppPage(model, layout, page.id, f) { pop(); pop() }
                is Page.Wallpapers -> Unit
            }
        } }
    }
}

@Composable
private fun ColumnScope.RootPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit, open: (Overlay) -> Unit) {
    val context = LocalContext.current
    val graph = context.app
    val scope = rememberCoroutineScope()
    PanelTitle("Settings")
    MenuRow("Appearance", { push(Page.Appearance) }, f, value = cfg.theme.name, chevron = true)
    MenuRow("Display & Text Size", { push(Page.DisplayText) }, value = textSizeName(cfg.textScale), chevron = true)
    MenuRow("Control Center", { push(Page.ControlCenterTiles) }, chevron = true)
    MenuRow("Set Up from Phone", {
        open(Overlay.PhoneSetup(
            "Glass Launcher Setup",
            listOf(
                PhoneField("tmdb", "TMDB API key or read token", cfg.featured.tmdbKey, "For Netflix, Prime Video, Apple TV+… rows", secret = true),
                PhoneField("youtube", "YouTube Data API key", cfg.featured.youtubeKey, "For the YouTube row", secret = true),
                PhoneField("city", "Weather city", cfg.weather?.city?.substringBefore(',') ?: "", "e.g. Toronto"),
                PhoneField("wallpaper", "Wallpaper image URL", "", "https://…"),
            ),
        ) { v ->
            v["tmdb"]?.takeIf { it.isNotBlank() }?.let { k -> model.edit { it.copy(featured = it.featured.copy(tmdbKey = k)) } }
            v["youtube"]?.takeIf { it.isNotBlank() }?.let { k -> model.edit { it.copy(featured = it.featured.copy(youtubeKey = k)) } }
            v["city"]?.takeIf { it.isNotBlank() }?.let { q ->
                scope.launch { Weather.geocode(graph.http, q, cfg.weather?.fahrenheit ?: false)?.let { w -> model.edit { it.copy(weather = w) } } }
            }
            v["wallpaper"]?.takeIf { it.isNotBlank() }?.let { url ->
                scope.launch {
                    if (graph.wallpapers.downloadUrl(url)) model.edit {
                        val w = Wallpaper(WallpaperKind.Url, url)
                        if (it.theme == ThemeMode.Light) it.copy(wallpaperLight = w) else it.copy(wallpaperDark = w)
                    }
                }
            }
        })
    }, value = "QR code")
    MenuRow("Top Shelf Content", { push(Page.Featured) }, value = featuredSummary(cfg.featured), chevron = true)
    MenuRow("Hidden Apps", { push(Page.Hidden) }, value = cfg.hidden.size.toString(), chevron = true)
    MenuRow("Icon Pack", { push(Page.IconPack) }, value = if (cfg.iconPack == null) "None" else "On", chevron = true)
    MenuRow("Screensaver", { push(Page.Screensaver) }, chevron = true)
    MenuRow("Widgets", { push(Page.Widgets) }, chevron = true)
    MenuRow("Accessibility", { push(Page.Accessibility) }, chevron = true)
    MenuRow("Home Button", { push(Page.HomeButton) }, chevron = true)
    MenuRow("Remote Buttons", { push(Page.RemoteButtons) }, chevron = true)
    // Only on rooted devices (su present and granted).
    val rooted by androidx.compose.runtime.produceState(dev.glasslauncher.system.Root.known) { value = dev.glasslauncher.system.Root.available() }
    MenuRow("Root", { push(Page.RootTools) }, value = if (rooted) "Detected" else "Not detected", chevron = true)
    MenuRow("Updates", { push(Page.Updates) }, value = BuildConfig.VERSION_NAME, chevron = true)
    MenuRow("Backup & Restore", { push(Page.Backup) }, chevron = true)
    MenuRow("About", { push(Page.About) }, chevron = true)
}

private fun featuredSummary(fc: dev.glasslauncher.data.FeaturedConfig) = when {
    fc.mode == FeaturedMode.Off || fc.source == FeaturedSourceId.Off -> "Off"
    fc.mode == FeaturedMode.FocusedApp -> "Focused App"
    else -> sourceName(fc.source)
}

private fun sourceName(id: FeaturedSourceId) = when (id) {
    FeaturedSourceId.Off -> "Off"
    FeaturedSourceId.Stremio -> "Stremio"
    FeaturedSourceId.Tmdb -> "TMDB"
    FeaturedSourceId.YouTube -> "YouTube"
    FeaturedSourceId.Plex -> "Plex"
    FeaturedSourceId.ContinueWatching -> "Continue Watching"
    FeaturedSourceId.TvApp -> "App's Own Row"
}

@Composable
private fun ColumnScope.AppearancePage(model: HomeModel, cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit) {
    val screen = dev.glasslauncher.ui.LocalScreenDissolve.current
    PanelTitle("Appearance")
    MenuRow("Theme", {
        val next = ThemeMode.entries[(cfg.theme.ordinal + 1) % ThemeMode.entries.size]
        screen.dissolve { model.edit { it.copy(theme = next) } }
    }, f, value = cfg.theme.name)
    MenuRow("Background", {
        val next = BackgroundMode.entries[(cfg.background.ordinal + 1) % BackgroundMode.entries.size]
        screen.dissolve { model.edit { it.copy(background = next) } }
    }, value = when (cfg.background) { BackgroundMode.Featured -> "Top Shelf"; BackgroundMode.Wallpaper -> "Wallpaper"; BackgroundMode.Motion -> "Motion (Aerials)" })
    Hint("Top Shelf fills Home with the focused app and its titles; Motion plays Apple's Aerial videos behind your apps.")
    MenuRow("Dark Mode Wallpaper", { push(Page.Wallpapers(dark = true)) }, value = wallpaperName(cfg.wallpaperDark), chevron = true)
    MenuRow("Light Mode Wallpaper", { push(Page.Wallpapers(dark = false)) }, value = wallpaperName(cfg.wallpaperLight), chevron = true)
    val fadeOptions = listOf(1, 3, 5, 10, 0)
    MenuRow("Fade Clock When Idle", {
        val next = fadeOptions[(fadeOptions.indexOf(cfg.idleFadeMinutes).coerceAtLeast(0) + 1) % fadeOptions.size]
        model.edit { it.copy(idleFadeMinutes = next) }
    }, value = if (cfg.idleFadeMinutes == 0) "Never" else "${cfg.idleFadeMinutes} min")
    Hint("Fading the clock and status bar when nothing is happening helps prevent burn-in on OLED and plasma TVs.")
}

private fun wallpaperName(w: Wallpaper) = when (w.kind) {
    WallpaperKind.Preset -> WallpaperLoader.presets.firstOrNull { it.id == w.value }?.name ?: "Preset"
    WallpaperKind.File -> "Image"
    WallpaperKind.Url -> "Web Image"
}

@Composable
private fun WallpaperPage(dark: Boolean, model: HomeModel, cfg: LauncherConfig, active: Boolean, open: (Overlay) -> Unit) {
    val graph = LocalContext.current.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val current = if (dark) cfg.wallpaperDark else cfg.wallpaperLight
    fun set(w: Wallpaper) = model.edit { if (dark) it.copy(wallpaperDark = w) else it.copy(wallpaperLight = w) }
    val first = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) { delay(16); runCatching { first.requestFocus() } } }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 22.dp)) {
        PanelTitle(if (dark) "Dark Wallpaper" else "Light Wallpaper")
        WallpaperLoader.presets.forEachIndexed { i, preset ->
            val swatch = remember(preset.id) { graph.wallpapers.renderPreset(preset, 96, 54).asImageBitmap() }
            MenuRow(
                preset.name,
                { set(Wallpaper(WallpaperKind.Preset, preset.id)) },
                if (i == 0) Modifier.focusRequester(first) else Modifier,
                value = if (current.kind == WallpaperKind.Preset && current.value == preset.id) "✓" else if (preset.light) "Light" else "Dark",
                leading = {
                    Image(swatch, null, contentScale = ContentScale.Crop, modifier = Modifier.width(44.dp).height(25.dp).graphicsLayer { shape = Shapes.row; clip = true })
                },
            )
        }
        MenuRow("Image from URL…", {
            open(Overlay.TextInput("Wallpaper URL", if (current.kind == WallpaperKind.Url) current.value else "https://", "A large landscape image works best.") { url ->
                scope.launch {
                    status = "Downloading…"
                    if (graph.wallpapers.downloadUrl(url)) { set(Wallpaper(WallpaperKind.Url, url)); status = null }
                    else status = "Couldn't load an image from that address."
                }
            })
        }, value = if (current.kind == WallpaperKind.Url) "✓" else null)
        status?.let { Hint(it) }
        SectionLabel("On this device")
        ImagePicker(
            modifier = Modifier.weight(1f),
            onPicked = { uri ->
                scope.launch {
                    status = "Importing…"
                    val path = graph.wallpapers.importImage(uri, "wallpaper-${if (dark) "dark" else "light"}-${System.currentTimeMillis()}")
                    if (path != null) { set(Wallpaper(WallpaperKind.File, path)); status = null } else status = "Couldn't read that image."
                }
            },
        )
    }
}

@Composable
private fun ColumnScope.FeaturedPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, open: (Overlay) -> Unit, push: (Page) -> Unit) {
    val graph = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val fc = cfg.featured
    fun setFeatured(transform: (dev.glasslauncher.data.FeaturedConfig) -> dev.glasslauncher.data.FeaturedConfig) =
        model.edit { it.copy(featured = transform(it.featured)) }

    PanelTitle("Top Shelf Content")
    // tvOS 27: the focused app's own hero at once, its titles after resting on it. Never keeps the hero
    // only (no title fetches, no slideshow bakes).
    MenuRow("Show Titles", { model.edit { it.copy(topShelfTitles = !it.topShelfTitles) } }, f,
        value = if (cfg.topShelfTitles) "Automatically" else "Never")
    // Turning it off keeps the default source, so turning it back on restores it.
    val off = fc.mode == FeaturedMode.Off || fc.source == FeaturedSourceId.Off
    val defaultSource = fc.source.takeIf { it != FeaturedSourceId.Off } ?: FeaturedSourceId.Stremio
    SectionLabel("Show content from")
    MenuRow("Focused App", { setFeatured { it.copy(mode = FeaturedMode.FocusedApp, source = defaultSource) } }, value = if (!off && fc.mode == FeaturedMode.FocusedApp) "✓" else null)
    MenuRow("One Source", { setFeatured { it.copy(mode = FeaturedMode.OneSource, source = defaultSource) } }, value = if (!off && fc.mode == FeaturedMode.OneSource) "✓" else null)
    MenuRow("Off", { setFeatured { it.copy(mode = FeaturedMode.Off) } }, value = if (off) "✓" else null)
    if (off) return
    if (fc.mode == FeaturedMode.FocusedApp) {
        Hint("The shelf shows the focused top-row app's content: Stremio, YouTube and Plex from their own catalogs; Netflix, Prime Video, Disney+, Apple TV, Max and Hulu from TMDB. Other apps, or services not set up below, show the default source.")
    }
    SectionLabel("Default Source")
    val tvRows = dev.glasslauncher.featured.TvRows.available(LocalContext.current)
    FeaturedSourceId.entries.filter { it != FeaturedSourceId.Off && it != FeaturedSourceId.TvApp && (it != FeaturedSourceId.ContinueWatching || tvRows) }.forEach { id ->
        MenuRow(sourceName(id), { setFeatured { it.copy(source = id) } }, value = if (fc.source == id) "✓" else null)
    }
    if (fc.mode == FeaturedMode.FocusedApp) {
        // Every service an app can use, whichever is the default.
        SectionLabel("Services")
        MenuRow("TMDB API Key", {
            open(Overlay.TextInput("TMDB API Key", fc.tmdbKey, "Free at themoviedb.org → Settings → API. Used for Netflix, Prime Video, Disney+, Apple TV, Max and Hulu.") { k -> setFeatured { it.copy(tmdbKey = k) } })
        }, value = mask(fc.tmdbKey))
        MenuRow("YouTube API Key", {
            open(Overlay.TextInput("YouTube Data API Key", fc.youtubeKey, "Create one in Google Cloud Console with YouTube Data API v3 enabled.") { k -> setFeatured { it.copy(youtubeKey = k) } })
        }, value = mask(fc.youtubeKey))
        if (fc.plexToken.isBlank()) MenuRow("Plex", { push(Page.PlexLink) }, value = "Sign In")
        else {
            val askPlex = dev.glasslauncher.home.LocalConfirm.current
            MenuRow("Plex", { askPlex(dev.glasslauncher.home.Overlay.Confirm("Sign Out of Plex?", "The Top Shelf stops showing your Plex titles until you sign in again.", "Sign Out", destructive = true) { setFeatured { it.copy(plexToken = "") } }) }, value = "Sign Out")
        }
    }
    when (fc.source) {
        FeaturedSourceId.Stremio -> {
            SectionLabel("Catalog")
            Sources.stremioCatalogs.forEach { (key, name) ->
                MenuRow(name, { setFeatured { it.copy(stremioCatalog = key) } }, value = if (fc.stremioCatalog == key) "✓" else null)
            }
            Hint("From Stremio's Cinemeta catalog. Selecting a title opens it in Stremio.")
        }
        FeaturedSourceId.Tmdb -> {
            SectionLabel("TMDB")
            MenuRow("API Key", {
                open(Overlay.TextInput("TMDB API Key", fc.tmdbKey, "Free at themoviedb.org → Settings → API. Either the v3 key or the read access token works.") { k -> setFeatured { it.copy(tmdbKey = k) } })
            }, value = mask(fc.tmdbKey))
            MenuRow("Region", {
                open(Overlay.TextInput("Region (2-letter country code)", fc.tmdbRegion) { r -> setFeatured { it.copy(tmdbRegion = r.uppercase().take(2)) } })
            }, value = fc.tmdbRegion)
            SectionLabel("Service")
            Sources.tmdbProviders.forEach { (key, name) ->
                MenuRow(name, { setFeatured { it.copy(tmdbProvider = key) } }, value = if (fc.tmdbProvider == key) "✓" else null)
            }
            Hint("This product uses the TMDB API but is not endorsed or certified by TMDB. Streaming availability data provided by JustWatch.")
        }
        FeaturedSourceId.YouTube -> {
            SectionLabel("YouTube")
            MenuRow("API Key", {
                open(Overlay.TextInput("YouTube Data API Key", fc.youtubeKey, "Create one in Google Cloud Console with YouTube Data API v3 enabled.") { k -> setFeatured { it.copy(youtubeKey = k) } })
            }, value = mask(fc.youtubeKey))
            MenuRow("Region", {
                open(Overlay.TextInput("Region (2-letter country code)", fc.youtubeRegion) { r -> setFeatured { it.copy(youtubeRegion = r.uppercase().take(2)) } })
            }, value = fc.youtubeRegion)
        }
        FeaturedSourceId.Plex -> {
            SectionLabel("Plex")
            if (fc.plexToken.isBlank()) MenuRow("Sign In", { push(Page.PlexLink) })
            else {
                val askPlex = dev.glasslauncher.home.LocalConfirm.current
                MenuRow("Sign Out", { askPlex(dev.glasslauncher.home.Overlay.Confirm("Sign Out of Plex?", "The Top Shelf stops showing your Plex titles until you sign in again.", "Sign Out", destructive = true) { setFeatured { it.copy(plexToken = "") } }) }, value = "Signed in")
            }
            Hint("Shows your On Deck items from the first Plex server that answers.")
        }
        FeaturedSourceId.ContinueWatching -> Hint("What you were watching in any app, from the rows apps publish to the TV (Watch Next and their own Continue Watching rows).")
        FeaturedSourceId.Off, FeaturedSourceId.TvApp -> Unit
    }
    MenuRow("Refresh Now", { scope.launch { graph.featured.refresh(fc, force = true) } })
}

private fun mask(key: String) = if (key.isBlank()) "Not set" else "••••" + key.takeLast(4)

@Composable
private fun ColumnScope.PlexLinkPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, done: () -> Unit) {
    val graph = LocalContext.current.app
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val clientId = cfg.featured.plexClientId.ifBlank { UUID.randomUUID().toString() }
        if (cfg.featured.plexClientId.isBlank()) model.edit { it.copy(featured = it.featured.copy(plexClientId = clientId)) }
        val pin = runCatching { Plex.createPin(graph.http, clientId) }.getOrNull()
        if (pin == null) {
            error = "Couldn't reach plex.tv"
        } else {
            code = pin.second
            var token: String? = null
            var attempts = 0
            while (token == null && attempts < 150) {
                delay(2000)
                token = Plex.pollPin(graph.http, clientId, pin.first)
                attempts++
            }
            if (token != null) {
                model.edit { it.copy(featured = it.featured.copy(plexToken = token, plexClientId = clientId)) }
                done()
            } else error = "The code expired. Go back and try again."
        }
    }
    PanelTitle("Sign In to Plex")
    Hint("On your phone or computer, go to plex.tv/link and enter:")
    Text(code ?: "…", style = Type.title.copy(fontSize = Type.title.fontSize * 1.6f), color = LocalPalette.current.primary, modifier = Modifier.padding(18.dp))
    error?.let { Hint(it) }
    MenuRow("Cancel", done, f)
}

@Composable
private fun ColumnScope.HiddenPage(model: HomeModel, cfg: LauncherConfig, layout: HomeLayout, f: Modifier, push: (Page) -> Unit, refocus: () -> Unit) {
    PanelTitle("Hidden Apps")
    MenuRow("Hide Apps…", { push(Page.HideMore) }, f)
    val hidden = layout.installed.filter { it.packageName in cfg.hidden }
    if (hidden.isEmpty()) Hint("No apps are hidden.") else SectionLabel("Select to show again")
    hidden.forEach { app -> MenuRow(app.label, { model.unhide(app.packageName); refocus() }, value = "Show") }
}

@Composable
private fun ColumnScope.HideMorePage(model: HomeModel, layout: HomeLayout, f: Modifier, refocus: () -> Unit) {
    PanelTitle("Hide Apps")
    val visible = layout.dock + layout.grid.flatMap {
        when (it) {
            is dev.glasslauncher.home.GridItem.App -> listOf(it.app)
            is dev.glasslauncher.home.GridItem.FolderItem -> it.apps
        }
    }
    visible.sortedBy { it.label.lowercase() }.forEachIndexed { i, app ->
        MenuRow(app.label, { model.hide(app.packageName); refocus() }, if (i == 0) f else Modifier, value = "Hide")
    }
}

@Composable
private fun ColumnScope.IconPackPage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    val graph = LocalContext.current.app
    val packs = remember { graph.iconPacks.installed() }
    PanelTitle("Icon Pack")
    MenuRow("None", { model.edit { it.copy(iconPack = null) } }, f, value = if (cfg.iconPack == null) "✓" else null)
    packs.forEach { pack ->
        MenuRow(pack.label, { model.edit { it.copy(iconPack = pack.packageName) } }, value = if (cfg.iconPack == pack.packageName) "✓" else null)
    }
    Hint(if (packs.isEmpty()) "No icon packs installed yet." else "Apps the pack doesn't cover keep their normal tile.")
    SectionLabel("Get an Icon Pack")
    val context = LocalContext.current
    MenuRow("Search the Appstore", {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("amzn://apps/android?s=icon%20pack")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }, chevron = true)
    Hint("Icon packs are separate apps made for Android launchers: any pack labelled ADW or Nova compatible works. Install one (from the Appstore, or its APK with Downloader), then pick it here. Most packs are made for phones, so expect square icons on your tiles rather than TV banners.")
}

@Composable
private fun ColumnScope.ScreensaverPage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val isSystem = remember(refresh) { Screensaver.isSystemScreensaver(context) }
    val canWrite = remember { Screensaver.canWriteSecureSettings(context) }
    val sc = cfg.screensaver
    PanelTitle("Screensaver")
    // Glass's Aerials (started by Glass after Home has been idle), or Fire TV's own screensaver.
    SectionLabel("Screensaver")
    MenuRow("Aerials", {
        model.edit { it.copy(screensaverMode = ScreensaverMode.Aerials) }
    }, f, value = if (cfg.screensaverMode == ScreensaverMode.Aerials) "✓" else null)
    MenuRow("Fire TV Screensaver", {
        model.edit { it.copy(screensaverMode = ScreensaverMode.System) }
    }, value = if (cfg.screensaverMode == ScreensaverMode.System) "✓" else null)
    if (cfg.screensaverMode == ScreensaverMode.System) {
        MenuRow("Fire TV Screensaver Settings", {
            SystemControls.openTvSettings(context, SystemControls.tvSettingsSections.first { it.title == "Display & Sounds" })
        }, chevron = true)
        Hint("Fire TV's own screensaver runs after the delay set in its Display settings.")
        return
    }
    MenuRow("Preview Aerials", { AerialActivity.start(context) })
    MenuRow(
        "Use as System Screensaver",
        { if (Screensaver.setAsSystemScreensaver(context)) refresh++ },
        value = when {
            isSystem -> "Active"
            canWrite -> "Set"
            else -> "Needs permission"
        },
        enabled = canWrite || isSystem,
    )
    if (!canWrite && !isSystem) Hint("One-time setup from a computer: ${Screensaver.GRANT_COMMAND}")
    if (HomeSetup.isFireTv) {
        Hint("Fire OS only runs Amazon's own screensavers. Use \"Start Aerials on Home After\" below to get Aerials on this TV.")
    }
    SectionLabel("Aerials Options")
    MenuRow("Quality", {
        model.edit { it.copy(screensaver = sc.copy(quality = if (sc.quality == AerialQuality.Hd1080) AerialQuality.Uhd4k else AerialQuality.Hd1080)) }
    }, value = if (sc.quality == AerialQuality.Hd1080) "1080p" else "4K")
    ToggleRow("Show Location", sc.showLocation, { v -> model.edit { it.copy(screensaver = sc.copy(showLocation = v)) } })
    ToggleRow("Show Clock", sc.showClock, { v -> model.edit { it.copy(screensaver = sc.copy(showClock = v)) } })
    val idleOptions = listOf(3, 5, 10, 15, 30)
    MenuRow("Start Aerials on Home After", {
        val next = idleOptions[(idleOptions.indexOf(cfg.aerialsIdleMinutes).coerceAtLeast(0) + 1) % idleOptions.size]
        model.edit { it.copy(aerialsOnIdleMinutes = next) }
    }, value = "${cfg.aerialsIdleMinutes} min")
    Hint("Aerial videos stream from Apple and are cached (up to 600 MB) so they replay offline.")
}

@Composable
private fun ColumnScope.WidgetsPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, open: (Overlay) -> Unit) {
    val context = LocalContext.current
    val graph = context.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    PanelTitle("Widgets")
    ToggleRow("24-Hour Time", cfg.clock24h, { v -> model.edit { it.copy(clock24h = v) } }, f)
    SectionLabel("Weather")
    MenuRow("Location", {
        open(Overlay.TextInput("Weather Location", cfg.weather?.city?.substringBefore(',') ?: "", "City name, e.g. Toronto") { q ->
            scope.launch {
                status = "Looking up $q…"
                val w = Weather.geocode(graph.http, q, cfg.weather?.fahrenheit ?: false)
                if (w != null) { model.edit { it.copy(weather = w) }; status = null } else status = "Couldn't find \"$q\"."
            }
        })
    }, value = cfg.weather?.city ?: "Not set")
    cfg.weather?.let { w ->
        ToggleRow("Fahrenheit", w.fahrenheit, { v -> model.edit { it.copy(weather = w.copy(fahrenheit = v)) } })
        ToggleRow("Show Weather", true, { model.edit { it.copy(weather = null) } })
    }
    if (cfg.weather == null) Hint("Set a location to show the weather next to the time.")
    status?.let { Hint(it) }
    SectionLabel("Now Playing")
    ToggleRow("Show on Home", cfg.showNowPlaying, { v -> model.edit { it.copy(showNowPlaying = v) } })
    Hint("While music plays, Home shows the album art and track. Control Center always shows playback controls.")
    if (!NowPlayingSource.isAllowed(context)) {
        Hint("Needs media access. From a computer: adb shell cmd notification allow_listener ${context.packageName}/${dev.glasslauncher.widgets.NowPlayingService::class.java.name}")
    }
    Hint("Weather by Open-Meteo.")
}

@Composable
private fun ColumnScope.HomeButtonPage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val isDefault = remember(refresh) { HomeSetup.isDefaultHome(context) }
    val guardOn = remember(refresh) { HomeSetup.isGuardEnabled(context) }
    val canWrite = remember { Screensaver.canWriteSecureSettings(context) }
    val requestIntent = remember(refresh) { HomeSetup.requestDefaultHomeIntent(context) }
    PanelTitle("Home Button")
    MenuRow("Default Home App", { refresh++ }, f, value = if (isDefault) "Glass Launcher" else "Another app")
    if (!isDefault && requestIntent != null) {
        MenuRow("Make Glass Launcher the Default", { runCatching { context.startActivity(requestIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
    }
    if (HomeSetup.isFireTv || !isDefault) {
        SectionLabel("Fallback")
        val askGuard = dev.glasslauncher.home.LocalConfirm.current
        ToggleRow("Home Button Takeover", cfg.homeGuard, { enable ->
            val apply = {
                model.edit { it.copy(homeGuard = enable) }
                if (canWrite) HomeSetup.setGuardEnabled(context, enable)
                refresh++
            }
            // Turning it off can leave the stock home screen in charge: ask first.
            if (enable) apply() else askGuard(dev.glasslauncher.home.Overlay.Confirm("Turn Off Home Button Takeover?", "If the stock home screen comes back, Glass Launcher won't take over again.", "Turn Off", destructive = true) { apply() })
        })
        Hint("When the stock home screen appears, Glass Launcher takes over. It only watches for the stock launcher's window and never intercepts buttons.")
        if (cfg.homeGuard && !guardOn) {
            Hint(if (canWrite) "Couldn't enable the accessibility service." else "Grant once from a computer: ${Screensaver.GRANT_COMMAND}, then toggle again. Or enable it in Accessibility settings.")
        }
    }
    if (HomeSetup.isFireTv) {
        SectionLabel("Most reliable on Fire TV (from a computer)")
        Hint(HomeSetup.DISABLE_STOCK_COMMAND)
        Hint("Undo with: ${HomeSetup.RESTORE_STOCK_COMMAND}")
    }
}

@Composable
private fun ColumnScope.UpdatesPage(f: Modifier) {
    val context = LocalContext.current
    val graph = context.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<Release?>(null) }
    PanelTitle("Updates")
    MenuRow("Current Version", {}, f, value = BuildConfig.VERSION_NAME)
    MenuRow("Check for Updates", {
        scope.launch {
            status = "Checking…"
            val latest = runCatching { Updater.latest(graph.http) }.getOrNull()
            release = latest?.takeIf { Updater.isNewer(it.version) }
            status = when {
                latest == null -> "Couldn't reach GitHub."
                release == null -> "You're up to date."
                else -> null
            }
        }
    })
    release?.let { r ->
        val askUpdate = dev.glasslauncher.home.LocalConfirm.current
        MenuRow("Install ${r.version}", { askUpdate(dev.glasslauncher.home.Overlay.Confirm("Glass Launcher ${r.version}", "Download and install it now? Glass restarts when it's done.", "Download and Install") {
            if (!Updater.canInstall(context)) {
                runCatching { context.startActivity(Updater.unknownSourcesIntent(context)) }
                status = "Allow Glass Launcher to install apps, then select Install again."
            } else scope.launch {
                runCatching {
                    Updater.downloadAndInstall(context, graph.http, r) { p -> status = "Downloading… ${(p * 100).toInt()}%" }
                }.onSuccess { status = "Installing…" }.onFailure { status = it.message ?: "Update failed." }
            }
        }) })
        if (r.notes.isNotBlank()) Hint(r.notes.take(400))
    }
    status?.let { Hint(it) }
    Hint("Updates come from github.com/${BuildConfig.UPDATE_REPO}/releases.")
}

@Composable
private fun ColumnScope.BackupPage(model: HomeModel, f: Modifier) {
    val context = LocalContext.current
    val graph = context.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = Backup.readUri(context, uri)
            status = if (text != null && runCatching { graph.config.import(text) }.isSuccess) "Restored." else "That file isn't a Glass Launcher backup."
        }
    }
    val canBrowse = remember {
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").resolveActivity(context.packageManager) != null
    }
    PanelTitle("Backup & Restore")
    MenuRow("Save Backup", {
        scope.launch { status = runCatching { "Saved to " + Backup.export(context, graph.config.export()) }.getOrElse { it.message ?: "Couldn't save." } }
    }, f)
    val askRestore = dev.glasslauncher.home.LocalConfirm.current
    MenuRow("Restore from Downloads", { askRestore(dev.glasslauncher.home.Overlay.Confirm("Restore from Downloads?", "Your current layout, folders and settings are replaced by the backup's.", "Restore", destructive = true) {
        scope.launch {
            val text = runCatching { Backup.read(context) }.getOrNull()
            status = when {
                text == null -> "No ${Backup.FILE_NAME} found in Downloads."
                runCatching { graph.config.import(text) }.isSuccess -> "Restored."
                else -> "The backup file couldn't be read."
            }
        }
    }) })
    if (canBrowse) MenuRow("Restore from File…", {
        askRestore(dev.glasslauncher.home.Overlay.Confirm("Restore from a File?", "Your current layout, folders and settings are replaced by the file's.", "Choose File", destructive = true) { pick.launch(arrayOf("application/json", "*/*")) })
    })
    status?.let { Hint(it) }
    Hint("Backups include your layout, folders, hidden apps and settings. Custom images stay on this TV.")
}

@Composable
private fun ColumnScope.AboutPage(f: Modifier) {
    PanelTitle("Glass Launcher")
    MenuRow("Version", {}, f, value = "${BuildConfig.VERSION_NAME} (${Build.MODEL})")
    Hint("Open source under the Apache License 2.0. github.com/${BuildConfig.UPDATE_REPO}")
    Hint("Aerial videos are streamed from Apple. Featured content from Stremio Cinemeta, TMDB, YouTube or Plex using your own keys. This product uses the TMDB API but is not endorsed or certified by TMDB. Weather by Open-Meteo.")
    SectionLabel("Privacy")
    MenuRow("Glass Launcher Collects No Data", {})
    Hint("No accounts, analytics, ads or tracking. Nothing about you, your apps or what you watch leaves this TV. Settings and caches (including the app switcher's blurred previews) stay on the device. The launcher only contacts the services you turn on (featured artwork, weather, Aerials, update checks), and sends them nothing but the request itself, such as your weather city or your own API keys.")
    Box(Modifier.size(1.dp))
}

@Composable
private fun ColumnScope.AccessibilityPage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    PanelTitle("Accessibility")
    MenuRow("Reduce Motion", {
        val next = Auto.entries[(cfg.reduceMotion.ordinal + 1) % Auto.entries.size]
        model.edit { it.copy(reduceMotion = next) }
    }, f, value = when (cfg.reduceMotion) { Auto.Auto -> "Automatic"; Auto.On -> "On"; Auto.Off -> "Off" })
    Hint("Turns off tilt, wiggle and movement; changes still dissolve. Automatic follows the system's animation setting.")
    Hint("Text size, bold text, contrast and transparency are in Display & Text Size.")
    ToggleRow("Navigation Sounds", cfg.sounds, { v -> model.edit { it.copy(sounds = v) } })
    Hint("Plays the system focus and click sounds, if they're enabled in the TV's settings.")
}

@Composable
private fun ColumnScope.RemoteButtonsPage(cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit) {
    val context = LocalContext.current
    val active = remember { RemoteButtons.takeoverActive() }
    val apps = remember { context.packageManager }
    val palette = LocalPalette.current
    PanelTitle("Remote Buttons")
    // The four app buttons, laid out as they sit on the remote.
    val (grid, others) = RemoteButtons.all.partition { it.id.startsWith("app") }
    grid.chunked(2).forEachIndexed { r, pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            pair.forEachIndexed { c, b ->
                val n = r * 2 + c + 1
                val action = actionName(RemoteButtons.action(b, cfg.remoteButtons), apps)
                FocusTile(
                    label = "${b.label}, $action",
                    onClick = { push(Page.ButtonAction(b.id)) },
                    shape = Shapes.pill,
                    focusedScale = 1.03f,
                    shadow = false,
                    modifier = (if (n == 1) f else Modifier).weight(1f).height(72.dp).testTag("remote-button-$n"),
                ) { focused ->
                    Column(
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                            .background(if (focused) palette.focusFill else palette.primary.copy(alpha = if (palette.light) 0.06f else 0.08f))
                            .padding(horizontal = 20.dp),
                    ) {
                        Text(b.label, style = Type.body, color = if (focused) palette.onFocusFill else palette.primary, maxLines = 1)
                        Text(action, style = Type.secondary, color = if (focused) palette.onFocusFill.copy(alpha = 0.7f) else palette.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    others.forEach { b ->
        MenuRow(b.label, { push(Page.ButtonAction(b.id)) }, value = actionName(RemoteButtons.action(b, cfg.remoteButtons), apps), chevron = true)
    }
    if (!active) {
        Hint("Fire TV keeps these buttons to itself. On a rooted TV, run this once from a computer, then restart the TV: ${RemoteButtons.INSTALL_COMMAND}")
    } else if (!HomeSetup.isRemoteKeysEnabled(context)) {
        Hint("Turn on Glass Launcher Remote Buttons in the TV's Accessibility settings.")
    }
    Hint("Home, Back, volume, mute, power, Alexa and the TV button keep working as usual.")
}

@Composable
private fun ColumnScope.ButtonActionPage(model: HomeModel, cfg: LauncherConfig, id: String, f: Modifier, push: (Page) -> Unit, done: () -> Unit) {
    val button = RemoteButtons.all.first { it.id == id }
    val current = RemoteButtons.action(button, cfg.remoteButtons)
    val apps = LocalContext.current.packageManager
    fun set(action: RemoteAction) {
        model.edit { it.copy(remoteButtons = it.remoteButtons + (id to action.key)) }
        done()
    }
    PanelTitle(button.label)
    val options = listOf(button.default, RemoteAction.TvSettings, RemoteAction.ControlCenter, RemoteAction.AppSwitcher, RemoteAction.Home, RemoteAction.Nothing).distinct()
    options.forEachIndexed { i, a ->
        val label = if (a == button.default) "${actionName(a, apps)} (Default)" else actionName(a, apps)
        MenuRow(label, { set(a) }, if (i == 0) f else Modifier, value = if (a == current) "✓" else null)
    }
    MenuRow("Open Another App…", { push(Page.ButtonApp(id)) }, chevron = true,
        value = (current as? RemoteAction.OpenApp)?.takeIf { it != button.default }?.let { actionName(it, apps) })
}

@Composable
private fun ColumnScope.ButtonAppPage(model: HomeModel, layout: HomeLayout, id: String, f: Modifier, done: () -> Unit) {
    PanelTitle("Choose App")
    layout.installed.sortedBy { it.label.lowercase() }.forEachIndexed { i, app ->
        MenuRow(app.label, {
            model.edit { it.copy(remoteButtons = it.remoteButtons + (id to RemoteAction.OpenApp(app.packageName).key)) }
            done()
        }, if (i == 0) f else Modifier)
    }
}

private fun actionName(action: RemoteAction, pm: android.content.pm.PackageManager): String = when (action) {
    is RemoteAction.OpenApp -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(action.pkg, 0)).toString() }.getOrDefault(action.pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
    RemoteAction.TvSettings -> "TV Settings"
    RemoteAction.ControlCenter -> "Control Center"
    RemoteAction.AppSwitcher -> "App Switcher"
    RemoteAction.Home -> "Home"
    RemoteAction.Nothing -> "Do Nothing"
    RemoteAction.Default -> "Default"
}


@Composable
private fun ColumnScope.RootToolsPage(f: Modifier, push: (Page) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rf = dev.glasslauncher.system.RootFeatures
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var freed by remember { mutableStateOf<Int?>(null) }
    val system = remember(tick) { rf.systemAppState(context) }
    val takeover = remember(tick) { rf.homeTakeoverOn(context) }
    val fast = remember(tick) { rf.fast(context) }
    val memory by androidx.compose.runtime.produceState<Boolean?>(null, tick) { value = rf.memoryTuningOn() }
    fun act(what: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = what
        scope.launch { block(); busy = null; tick++ }
    }
    // Root isn't assumed: the first row says whether su is there and grants Glass root; without it,
    // everything below is greyed out.
    var rootCheck by remember { mutableStateOf(0) }
    val rooted by androidx.compose.runtime.produceState<Boolean?>(null, rootCheck) { value = dev.glasslauncher.system.Root.recheck() }
    val on = rooted == true
    PanelTitle("Root")
    MenuRow("Superuser", { rootCheck++ }, f, value = when (rooted) { null -> "Checking…"; true -> "Detected"; false -> "Not detected" })
    Hint(if (on) "Root changes are reversible and logged. Some need a restart." else "These need root (Magisk). Select Superuser to check again after granting Glass Launcher root.")
    SectionLabel("System")
    val askSystem = dev.glasslauncher.home.LocalConfirm.current
    MenuRow("System App", {
        val install = system == dev.glasslauncher.system.RootFeatures.SystemApp.Off || system == dev.glasslauncher.system.RootFeatures.SystemApp.Removing
        askSystem(dev.glasslauncher.home.Overlay.Confirm(
            if (install) "Install as a System App?" else "Remove the System App?",
            if (install) "Glass becomes a privileged system app after the next restart (a Magisk module; removing it reverts)." else "Glass goes back to a regular app after the next restart.",
            if (install) "Install" else "Remove", destructive = !install,
        ) { act("system") { if (install) rf.installSystemApp(context) else rf.removeSystemApp(context) } })
    }, enabled = on, value = when (system) {
        dev.glasslauncher.system.RootFeatures.SystemApp.On -> "On"
        dev.glasslauncher.system.RootFeatures.SystemApp.Pending -> "On after restart"
        dev.glasslauncher.system.RootFeatures.SystemApp.Removing -> "Off after restart"
        dev.glasslauncher.system.RootFeatures.SystemApp.Off -> "Off"
    })
    Hint("Runs Glass as a privileged system app: it can read other apps' TV rows (Continue Watching) and is harder for the system to stop.")
    ToggleRow("Home Takeover", takeover, { v -> act("home") { rf.setHomeTakeover(context, v) } }, enabled = on)
    Hint("Turns off Fire TV's own launcher and makes Glass the Home screen. Off brings Fire TV's back.")
    SectionLabel("Performance")
    MenuRow("Balanced", { act("perf") { rf.setFast(context, false) } }, value = if (!fast) "✓" else null, enabled = on)
    MenuRow("Fast", { act("perf") { rf.setFast(context, true) } }, value = if (fast) "✓" else null, enabled = on)
    Hint("Fast turns off system window animations, so switching apps is instant (Glass keeps its own motion), and holds the GPU and CPU at higher minimum clocks, which runs the stick warmer.")
    ToggleRow("Memory Tuning", memory == true, { v -> act("memory") { rf.setMemoryTuning(context, v) } }, enabled = on)
    Hint("Keeps more apps ready to resume: 1.2 GB compressed swap, and up to 12 background apps instead of 4. Takes effect after a restart.")
    MenuRow("Free Memory", { act("free") { freed = rf.freeMemory(context) } }, value = freed?.let { "$it MB freed" }, enabled = on)
    MenuRow("App Freezer", { push(Page.Freezer) }, chevron = true, enabled = on)
    SectionLabel("Device")
    val ask = dev.glasslauncher.home.LocalConfirm.current
    MenuRow("Restart Now", {
        ask(dev.glasslauncher.home.Overlay.Confirm("Restart Now?", "The TV restarts to apply root changes. Anything playing stops.", "Restart", destructive = true) { act("restart") { rf.restart(context) } })
    }, enabled = on)
    MenuRow("Root Log", { push(Page.RootLog) }, chevron = true, enabled = on)
}

@Composable
private fun ColumnScope.FreezerPage(f: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rf = dev.glasslauncher.system.RootFeatures
    var tick by remember { mutableStateOf(0) }
    val frozen by androidx.compose.runtime.produceState<Set<String>?>(null, tick) { value = rf.frozen() }
    PanelTitle("App Freezer")
    Hint("Turned-off apps stop running and disappear until turned back on. Nothing is uninstalled.")
    rf.freezable.forEachIndexed { i, app ->
        val off = frozen?.contains(app.pkg) == true
        ToggleRow(app.label, !off, { on -> scope.launch { rf.setFrozen(context, app.pkg, !on); tick++ } }, if (i == 0) f else Modifier, enabled = frozen != null)
        Hint(app.note)
    }
}

@Composable
private fun ColumnScope.RootLogPage(f: Modifier) {
    val context = LocalContext.current
    val lines = remember { dev.glasslauncher.system.Root.readLog(context) }
    PanelTitle("Root Log")
    if (lines.isEmpty()) MenuRow("Nothing yet", {}, f)
    lines.forEachIndexed { i, line -> MenuRow(line, {}, if (i == 0) f else Modifier) }
}

private val TEXT_SIZES = listOf(1f to "Default", 1.1f to "Large", 1.2f to "Larger", 1.3f to "Extra Large", 1.4f to "Largest")

private fun textSizeName(scale: Float) = TEXT_SIZES.minByOrNull { kotlin.math.abs(it.first - scale) }!!.second

/** tvOS 27's Display & Text Size page: Bold Text and Text Size, then Contrast. Fire TV's own options are linked, not copied. */
@Composable
private fun ColumnScope.DisplayTextPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit) {
    val context = LocalContext.current
    val screen = dev.glasslauncher.ui.LocalScreenDissolve.current
    PanelTitle("Display & Text Size")
    SectionLabel("Text")
    ToggleRow("Bold Text", cfg.boldText, { v -> screen.dissolve { model.edit { it.copy(boldText = v) } } }, f)
    MenuRow("Text Size", { push(Page.TextSize) }, value = textSizeName(cfg.textScale), chevron = true)
    SectionLabel("Contrast")
    ToggleRow("Increase Contrast", cfg.increaseContrast, { v -> screen.dissolve { model.edit { it.copy(increaseContrast = v) } } })
    ToggleRow("Reduce Transparency", cfg.reduceTransparency, { v -> screen.dissolve { model.edit { it.copy(reduceTransparency = v) } } })
    Hint("Reduce Transparency makes glass solid for easier reading.")
    SectionLabel("Fire TV")
    MenuRow("Fire TV Accessibility", {
        SystemControls.openTvSettings(context, SystemControls.tvSettingsSections.first { it.title == "Accessibility" })
    }, chevron = true)
    Hint("High Contrast Text, Screen Magnifier and captions are Fire TV settings and apply to every app.")
}

/** A five-step slider: Left/Right changes the size, and the page grows with it as you go. */
@Composable
private fun ColumnScope.TextSizePage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    val palette = LocalPalette.current
    val index = TEXT_SIZES.indexOfFirst { it.first == textSizeName(cfg.textScale).let { n -> TEXT_SIZES.first { s -> s.second == n }.first } }
    fun set(i: Int) { model.edit { it.copy(textScale = TEXT_SIZES[i.coerceIn(0, TEXT_SIZES.lastIndex)].first) } }
    PanelTitle("Text Size")
    // The size's name above the slider (tvOS 27).
    Text(TEXT_SIZES[index].second, style = Type.heading, color = palette.primary, modifier = Modifier.padding(start = 18.dp, bottom = 6.dp).testTag("text-size-name"))
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = f
            .fillMaxWidth()
            .background(if (focused) palette.focusFill else palette.primary.copy(alpha = 0.08f), Shapes.pill)
            .padding(horizontal = 22.dp, vertical = 16.dp)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.action != android.view.KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (k.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { set(index - 1); true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { set(index + 1); true }
                    else -> false
                }
            }
            .semantics {
                contentDescription = "Text Size, ${TEXT_SIZES[index].second}"
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(index.toFloat(), 0f..TEXT_SIZES.lastIndex.toFloat(), TEXT_SIZES.size - 2)
                setProgress { v -> set(v.toInt()); true }
            }
            .focusable()
            .testTag("text-size-slider"),
    ) {
        val fg = if (focused) palette.onFocusFill else palette.primary
        Text("A", style = Type.caption, color = fg)
        androidx.compose.foundation.Canvas(Modifier.weight(1f).height(24.dp)) {
            val y = size.height / 2
            drawLine(fg.copy(alpha = 0.3f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            val step = size.width / TEXT_SIZES.lastIndex
            for (i in TEXT_SIZES.indices) drawCircle(fg.copy(alpha = if (i <= index) 0.9f else 0.35f), 4.dp.toPx(), androidx.compose.ui.geometry.Offset(step * i, y))
            drawLine(fg, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(step * index, y), 4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(fg, 11.dp.toPx(), androidx.compose.ui.geometry.Offset(step * index, y))
        }
        Text("A", style = Type.title, color = fg)
    }
    Hint("Press left or right. Text and the layout around it grow together, here and across the launcher.")
}

/** Settings › Control Center: which tiles show. */
@Composable
private fun ColumnScope.ControlCenterTilesPage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    PanelTitle("Control Center")
    Hint("Choose what appears in Control Center. Settings is always there.")
    dev.glasslauncher.home.CONTROL_CENTER_TILES.forEachIndexed { i, (id, label) ->
        ToggleRow(label, id !in cfg.ccHidden, { on ->
            model.edit { it.copy(ccHidden = if (on) it.ccHidden - id else it.ccHidden + id) }
        }, if (i == 0) f else Modifier)
    }
}

