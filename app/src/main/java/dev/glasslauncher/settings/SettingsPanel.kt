package dev.glasslauncher.settings

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.key
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
import dev.glasslauncher.system.Release
import dev.glasslauncher.system.Updater
import dev.glasslauncher.ui.Hint
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.MenuRow
import dev.glasslauncher.ui.SectionLabel
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.ToggleRow
import dev.glasslauncher.ui.Type
import dev.glasslauncher.widgets.NowPlayingSource
import dev.glasslauncher.widgets.Weather
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

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

    key(stack.last()) {
        val page = stack.last()
        if (page is Page.Wallpapers) {
            WallpaperPage(page.dark, model, cfg, active, open)
        } else MenuList(active) { first ->
            val f = Modifier.focusRequester(first)
            when (page) {
                Page.Root -> RootPage(cfg, f, ::push)
                Page.Appearance -> AppearancePage(model, cfg, f, ::push)
                Page.Featured -> FeaturedPage(model, cfg, f, open, ::push)
                Page.PlexLink -> PlexLinkPage(model, cfg, f, ::pop)
                Page.Hidden -> HiddenPage(model, cfg, layout, f, ::push)
                Page.HideMore -> HideMorePage(model, layout, f)
                Page.IconPack -> IconPackPage(model, cfg, f)
                Page.Screensaver -> ScreensaverPage(model, cfg, f)
                Page.Widgets -> WidgetsPage(model, cfg, f, open)
                Page.HomeButton -> HomeButtonPage(model, cfg, f)
                Page.Updates -> UpdatesPage(f)
                Page.Backup -> BackupPage(model, f)
                Page.About -> AboutPage(f)
                is Page.Wallpapers -> Unit
            }
        }
    }
}

@Composable
private fun ColumnScope.RootPage(cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit) {
    PanelTitle("Settings")
    MenuRow("Appearance", { push(Page.Appearance) }, f, value = cfg.theme.name)
    MenuRow("Featured Row", { push(Page.Featured) }, value = sourceName(cfg.featured.source))
    MenuRow("Hidden Apps", { push(Page.Hidden) }, value = cfg.hidden.size.toString())
    MenuRow("Icon Pack", { push(Page.IconPack) }, value = if (cfg.iconPack == null) "None" else "On")
    MenuRow("Screensaver", { push(Page.Screensaver) })
    MenuRow("Widgets", { push(Page.Widgets) })
    MenuRow("Home Button", { push(Page.HomeButton) })
    MenuRow("Updates", { push(Page.Updates) }, value = BuildConfig.VERSION_NAME)
    MenuRow("Backup & Restore", { push(Page.Backup) })
    MenuRow("About", { push(Page.About) })
}

private fun sourceName(id: FeaturedSourceId) = when (id) {
    FeaturedSourceId.Off -> "Off"
    FeaturedSourceId.Stremio -> "Stremio"
    FeaturedSourceId.Tmdb -> "TMDB"
    FeaturedSourceId.YouTube -> "YouTube"
    FeaturedSourceId.Plex -> "Plex"
}

@Composable
private fun ColumnScope.AppearancePage(model: HomeModel, cfg: LauncherConfig, f: Modifier, push: (Page) -> Unit) {
    PanelTitle("Appearance")
    MenuRow("Theme", {
        val next = ThemeMode.entries[(cfg.theme.ordinal + 1) % ThemeMode.entries.size]
        model.edit { it.copy(theme = next) }
    }, f, value = cfg.theme.name)
    MenuRow("Dark Mode Wallpaper", { push(Page.Wallpapers(dark = true)) }, value = wallpaperName(cfg.wallpaperDark))
    MenuRow("Light Mode Wallpaper", { push(Page.Wallpapers(dark = false)) }, value = wallpaperName(cfg.wallpaperLight))
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

    PanelTitle("Featured Row")
    SectionLabel("Show content from")
    FeaturedSourceId.entries.forEachIndexed { i, id ->
        MenuRow(sourceName(id), { setFeatured { it.copy(source = id) } }, if (i == 0) f else Modifier, value = if (fc.source == id) "✓" else null)
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
            else MenuRow("Sign Out", { setFeatured { it.copy(plexToken = "") } }, value = "Signed in")
            Hint("Shows your On Deck items from the first Plex server that answers.")
        }
        FeaturedSourceId.Off -> Unit
    }
    if (fc.source != FeaturedSourceId.Off) {
        MenuRow("Refresh Now", { scope.launch { graph.featured.refresh(fc, force = true) } })
    }
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
private fun ColumnScope.HiddenPage(model: HomeModel, cfg: LauncherConfig, layout: HomeLayout, f: Modifier, push: (Page) -> Unit) {
    PanelTitle("Hidden Apps")
    MenuRow("Hide Apps…", { push(Page.HideMore) }, f)
    val hidden = layout.installed.filter { it.packageName in cfg.hidden }
    if (hidden.isEmpty()) Hint("No apps are hidden.") else SectionLabel("Select to show again")
    hidden.forEach { app -> MenuRow(app.label, { model.unhide(app.packageName) }, value = "Show") }
}

@Composable
private fun ColumnScope.HideMorePage(model: HomeModel, layout: HomeLayout, f: Modifier) {
    PanelTitle("Hide Apps")
    val visible = layout.dock + layout.grid.flatMap {
        when (it) {
            is dev.glasslauncher.home.GridItem.App -> listOf(it.app)
            is dev.glasslauncher.home.GridItem.FolderItem -> it.apps
        }
    }
    visible.sortedBy { it.label.lowercase() }.forEachIndexed { i, app ->
        MenuRow(app.label, { model.hide(app.packageName) }, if (i == 0) f else Modifier, value = "Hide")
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
    Hint(if (packs.isEmpty()) "No icon packs installed. Any ADW or Nova compatible icon pack works." else "Apps the pack doesn't cover keep their normal tile.")
}

@Composable
private fun ColumnScope.ScreensaverPage(model: HomeModel, cfg: LauncherConfig, f: Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val isSystem = remember(refresh) { Screensaver.isSystemScreensaver(context) }
    val canWrite = remember { Screensaver.canWriteSecureSettings(context) }
    val sc = cfg.screensaver
    PanelTitle("Screensaver")
    MenuRow("Preview Aerials", { context.startActivity(Intent(context, AerialActivity::class.java)) }, f)
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
    SectionLabel("Options")
    MenuRow("Quality", {
        model.edit { it.copy(screensaver = sc.copy(quality = if (sc.quality == AerialQuality.Hd1080) AerialQuality.Uhd4k else AerialQuality.Hd1080)) }
    }, value = if (sc.quality == AerialQuality.Hd1080) "1080p" else "4K")
    ToggleRow("Show Location", sc.showLocation, { v -> model.edit { it.copy(screensaver = sc.copy(showLocation = v)) } })
    ToggleRow("Show Clock", sc.showClock, { v -> model.edit { it.copy(screensaver = sc.copy(showClock = v)) } })
    Hint("Aerial videos stream from Apple and are cached (up to 600 MB) so they replay offline.")
}

@Composable
private fun ColumnScope.WidgetsPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, open: (Overlay) -> Unit) {
    val context = LocalContext.current
    val graph = context.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    PanelTitle("Widgets")
    MenuRow("Clock", { model.edit { it.copy(clock24h = !it.clock24h) } }, f, value = if (cfg.clock24h) "24-hour" else "12-hour")
    SectionLabel("Weather")
    MenuRow("Location", {
        open(Overlay.TextInput("Weather Location", cfg.weather?.city?.substringBefore(',') ?: "", "City name, e.g. Toronto") { q ->
            scope.launch {
                status = "Looking up $q…"
                val w = Weather.geocode(graph.http, q, cfg.weather?.fahrenheit ?: false)
                if (w != null) { model.edit { it.copy(weather = w) }; status = null } else status = "Couldn't find \"$q\"."
            }
        })
    }, value = cfg.weather?.city ?: "Off")
    cfg.weather?.let { w ->
        MenuRow("Units", { model.edit { it.copy(weather = w.copy(fahrenheit = !w.fahrenheit)) } }, value = if (w.fahrenheit) "°F" else "°C")
        MenuRow("Turn Off Weather", { model.edit { it.copy(weather = null) } })
    }
    status?.let { Hint(it) }
    SectionLabel("Now Playing")
    ToggleRow("Show Now Playing", cfg.showNowPlaying, { v -> model.edit { it.copy(showNowPlaying = v) } })
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
        MenuRow("Home Button Takeover", {
            val enable = !cfg.homeGuard
            model.edit { it.copy(homeGuard = enable) }
            if (canWrite) HomeSetup.setGuardEnabled(context, enable)
            refresh++
        }, value = when {
            cfg.homeGuard && guardOn -> "On"
            cfg.homeGuard -> "Needs permission"
            else -> "Off"
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
        MenuRow("Install ${r.version}", {
            if (!Updater.canInstall(context)) {
                runCatching { context.startActivity(Updater.unknownSourcesIntent(context)) }
                status = "Allow Glass Launcher to install apps, then select Install again."
                return@MenuRow
            }
            scope.launch {
                runCatching {
                    Updater.downloadAndInstall(context, graph.http, r) { p -> status = "Downloading… ${(p * 100).toInt()}%" }
                }.onSuccess { status = "Installing…" }.onFailure { status = it.message ?: "Update failed." }
            }
        })
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
    MenuRow("Restore from Downloads", {
        scope.launch {
            val text = runCatching { Backup.read(context) }.getOrNull()
            status = when {
                text == null -> "No ${Backup.FILE_NAME} found in Downloads."
                runCatching { graph.config.import(text) }.isSuccess -> "Restored."
                else -> "The backup file couldn't be read."
            }
        }
    })
    if (canBrowse) MenuRow("Restore from File…", { pick.launch(arrayOf("application/json", "*/*")) })
    status?.let { Hint(it) }
    Hint("Backups include your layout, folders, hidden apps and settings. Custom images stay on this TV.")
}

@Composable
private fun ColumnScope.AboutPage(f: Modifier) {
    PanelTitle("Glass Launcher")
    MenuRow("Version", {}, f, value = "${BuildConfig.VERSION_NAME} (${Build.MODEL})")
    Hint("Open source under the Apache License 2.0. github.com/${BuildConfig.UPDATE_REPO}")
    Hint("Aerial videos are streamed from Apple. Featured content from Stremio Cinemeta, TMDB, YouTube or Plex using your own keys. This product uses the TMDB API but is not endorsed or certified by TMDB. Weather by Open-Meteo.")
    Box(Modifier.size(1.dp))
}
