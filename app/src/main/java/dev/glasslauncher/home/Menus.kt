package dev.glasslauncher.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.glasslauncher.app
import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.appKey
import dev.glasslauncher.data.folderKey
import dev.glasslauncher.glass.GlassStyle
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.glass
import dev.glasslauncher.settings.ImagePicker
import dev.glasslauncher.settings.SettingsPanel
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.Hint
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.MenuRow
import dev.glasslauncher.ui.SectionLabel
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type
import dev.glasslauncher.system.PhoneField
import dev.glasslauncher.system.PhoneSetupServer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch

@Composable
fun OverlayContent(
    overlay: Overlay,
    model: HomeModel,
    layout: HomeLayout,
    cfg: LauncherConfig,
    active: Boolean,
    open: (Overlay) -> Unit,
    close: () -> Unit,
    closeAll: () -> Unit,
    startMove: (String) -> Unit,
) {
    when (overlay) {
        is Overlay.AppMenu -> SidePanel(active) { MenuList(active) { first -> AppMenuBody(overlay, model, layout, first, open, close, closeAll, startMove) } }
        is Overlay.FolderMenu -> SidePanel(active) {
            MenuList(active) { first ->
                PanelTitle(overlay.folder.name)
                MenuRow("Open", { closeAll(); open(Overlay.FolderOpen(overlay.folder.id)) }, Modifier.focusRequester(first))
                MenuRow("Rename", {
                    open(Overlay.TextInput("Rename Folder", overlay.folder.name) { model.renameFolder(overlay.folder.id, it); closeAll() })
                })
                MenuRow("Move", { startMove(folderKey(overlay.folder.id)) })
                MenuRow("Remove Folder", { model.deleteFolder(overlay.folder.id); closeAll() })
                Hint("Removing a folder puts its apps back on the home screen.")
            }
        }
        is Overlay.FolderPicker -> SidePanel(active) {
            MenuList(active) { first ->
                PanelTitle("Move to Folder")
                MenuRow("New Folder", {
                    val id = model.newFolder(overlay.app.packageName, "New Folder")
                    closeAll()
                    open(Overlay.TextInput("Name Folder", "New Folder") { model.renameFolder(id, it); closeAll() })
                }, Modifier.focusRequester(first))
                cfg.folders.filter { f -> layout.grid.any { it.key == folderKey(f.id) } }.forEach { f ->
                    MenuRow(f.name, { model.addToFolder(overlay.app.packageName, f.id); closeAll() }, value = "${f.apps.size}")
                }
            }
        }
        is Overlay.IconPicker -> SidePanel(active, width = 520.dp) { IconPickerBody(overlay.app, model, active, open, closeAll) }
        is Overlay.TextInput -> SidePanel(active) { TextInputBody(overlay, active, close, open) }
        is Overlay.PhoneSetup -> SidePanel(active, width = 460.dp) { PhoneSetupBody(overlay, active, close) }
        Overlay.Tips -> FullOverlay(active) { TipsCard(active) { model.edit { it.copy(tipsSeen = true) }; close() } }
        is Overlay.FolderOpen -> FolderView(overlay.folderId, model, layout, active, open, close)
        Overlay.Settings -> SidePanel(active, width = 480.dp) { SettingsPanel(model, cfg, layout, active, open, close) }
    }
}

/** Scrollable column of menu rows that grabs focus whenever its overlay becomes the active one. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MenuList(active: Boolean, content: @Composable ColumnScope.(FocusRequester) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(active) {
        if (active) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides MinimalScroll) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 22.dp),
        ) { content(first) }
    }
}

@Composable
fun PanelTitle(text: String) {
    Text(text, style = Type.title, color = LocalPalette.current.primary, modifier = Modifier.padding(start = 18.dp, bottom = 12.dp, top = 4.dp))
}

@Composable
private fun ColumnScope.AppMenuBody(
    overlay: Overlay.AppMenu,
    model: HomeModel,
    layout: HomeLayout,
    first: FocusRequester,
    open: (Overlay) -> Unit,
    close: () -> Unit,
    closeAll: () -> Unit,
    startMove: (String) -> Unit,
) {
    val app = overlay.app
    val art = rememberArt(model, app)
    Box(
        Modifier
            .padding(horizontal = 18.dp)
            .width(200.dp)
            .aspectRatio(16f / 9f)
            .graphicsLayer { shape = Shapes.tile; clip = true },
    ) { art?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } }
    PanelTitle(app.label)
    MenuRow("Open", { closeAll(); model.launch(app) }, Modifier.focusRequester(first))
    if (overlay.folderId == null) MenuRow("Move", { startMove(appKey(app.packageName)) })
    when {
        overlay.inDock -> MenuRow("Remove from Top Row", { model.removeFromDock(app.packageName); closeAll() })
        layout.dock.size < DOCK_SIZE -> MenuRow("Add to Top Row", { model.addToDock(app.packageName); closeAll() })
    }
    if (overlay.folderId != null) {
        MenuRow("Remove from Folder", { model.removeFromFolder(app.packageName, overlay.folderId); close() })
    } else {
        MenuRow("Move to Folder…", { open(Overlay.FolderPicker(app)) })
    }
    MenuRow("Change Icon…", { open(Overlay.IconPicker(app)) })
    MenuRow("Hide", { model.hide(app.packageName); closeAll() })
    MenuRow("App Info", { closeAll(); model.appInfo(app) })
    MenuRow("Uninstall", { closeAll(); model.uninstall(app) })
}

@Composable
private fun IconPickerBody(app: AppEntry, model: HomeModel, active: Boolean, open: (Overlay) -> Unit, closeAll: () -> Unit) {
    val graph = LocalContext.current.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 22.dp)) {
        PanelTitle("Icon for ${app.label}")
        val first = remember { FocusRequester() }
        LaunchedEffect(active) { if (active) { withFrameNanos { }; runCatching { first.requestFocus() } } }
        MenuRow("Use Default", { model.setCustomIcon(app.packageName, null); closeAll() }, Modifier.focusRequester(first))
        MenuRow("Image from URL…", {
            open(Overlay.TextInput("Icon Image URL", "https://", "Square images get a generated tile; wide images fill the tile.") { url ->
                scope.launch {
                    status = "Downloading…"
                    val path = graph.wallpapers.downloadImage(url, "icon-${app.packageName}-${System.currentTimeMillis()}")
                    if (path != null) { model.setCustomIcon(app.packageName, path); closeAll() } else status = "Couldn't load that image."
                }
            })
        })
        status?.let { Hint(it) }
        SectionLabel("On this device")
        ImagePicker(
            modifier = Modifier.weight(1f),
            onPicked = { uri ->
                scope.launch {
                    val path = graph.wallpapers.importImage(uri, "icon-${app.packageName}-${System.currentTimeMillis()}")
                    if (path != null) { model.setCustomIcon(app.packageName, path); closeAll() } else status = "Couldn't read that image."
                }
            },
        )
    }
}

@Composable
private fun TextInputBody(overlay: Overlay.TextInput, active: Boolean, close: () -> Unit, open: (Overlay) -> Unit) {
    val palette = LocalPalette.current
    var text by remember { mutableStateOf(overlay.initial) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var fieldFocused by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) { withFrameNanos { }; runCatching { field.requestFocus() }; keyboard?.show() }
    }
    var submitted by remember { mutableStateOf(false) }
    fun submit() {
        if (submitted) return
        submitted = true
        keyboard?.hide(); close(); overlay.onDone(text.trim())
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelTitle(overlay.title)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = Type.heading.copy(color = palette.primary),
            cursorBrush = SolidColor(palette.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, showKeyboardOnFocus = false),
            keyboardActions = KeyboardActions(onDone = { submit() }, onNext = { submit() }, onGo = { submit() }, onSearch = { submit() }, onSend = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .onFocusChanged { fieldFocused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    val k = e.nativeKeyEvent
                    when {
                        k.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER && k.action == AndroidKeyEvent.ACTION_UP -> { keyboard?.show(); true }
                        k.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && k.action == AndroidKeyEvent.ACTION_DOWN -> {
                            keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down); true
                        }
                        else -> false
                    }
                }
                .background(if (fieldFocused) palette.primary.copy(alpha = 0.16f) else palette.primary.copy(alpha = 0.08f), Shapes.row)
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .testTag("text-input"),
        )
        if (overlay.hint.isNotEmpty()) Hint(overlay.hint)
        Spacer(Modifier.height(8.dp))
        MenuRow("Done", { submit() })
        MenuRow("Type on Your Phone", {
            keyboard?.hide()
            open(Overlay.PhoneSetup(overlay.title, listOf(PhoneField("value", overlay.title, text, overlay.hint))) { values ->
                values["value"]?.takeIf { it.isNotBlank() }?.let { text = it; submit() }
            })
        })
        MenuRow("Cancel", { keyboard?.hide(); close() })
    }
}

@Composable
private fun FolderView(
    folderId: String,
    model: HomeModel,
    layout: HomeLayout,
    active: Boolean,
    open: (Overlay) -> Unit,
    close: () -> Unit,
) {
    val folder = layout.grid.filterIsInstance<GridItem.FolderItem>().firstOrNull { it.folder.id == folderId }
    LaunchedEffect(folder == null) { if (folder == null) close() }
    folder ?: return
    val palette = LocalPalette.current
    val first = remember { FocusRequester() }
    val lastFocused = remember { mutableStateOf<String?>(null) }
    val requesters = remember { HashMap<String, FocusRequester>() }
    LaunchedEffect(active, folder.apps.size) {
        if (active) {
            withFrameNanos { }
            val target = lastFocused.value?.let { requesters[it] } ?: first
            runCatching { target.requestFocus() }
        }
    }
    FullOverlay(active) {
        Box(
            Modifier
                .fillMaxSize()
                .glass(LocalBackdrop.current, RectangleShape, GlassStyle.overlay(palette.light).copy(highlight = 0f, rim = 0f))
                .background(if (palette.light) Color(0x22FFFFFF) else Color(0x33000000)),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(top = 70.dp, start = 48.dp, end = 48.dp),
        ) {
            FocusTile(
                label = "Folder name, ${folder.folder.name}",
                shape = Shapes.pill,
                focusedScale = 1.06f,
                onClick = { open(Overlay.TextInput("Rename Folder", folder.folder.name) { model.renameFolder(folderId, it) }) },
                modifier = Modifier.testTag("folder-title"),
            ) { focused ->
                Text(
                    folder.folder.name,
                    style = Type.title,
                    textAlign = TextAlign.Center,
                    color = if (focused) palette.onFocusFill else palette.primary,
                    modifier = Modifier
                        .background(if (focused) palette.focusFill else Color.Transparent, Shapes.pill)
                        .padding(horizontal = 26.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(34.dp))
            folder.apps.chunked(COLUMNS).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    row.forEachIndexed { i, app ->
                        val key = appKey(app.packageName)
                        val req = if (rowIndex == 0 && i == 0) first else requesters.getOrPut(key) { FocusRequester() }
                        requesters[key] = req
                        Box(Modifier.weight(1f)) {
                            AppCell(
                                app = app,
                                model = model,
                                moving = false,
                                focusRequester = req,
                                onFocused = { lastFocused.value = key },
                                onMenu = { open(Overlay.AppMenu(app, inDock = false, folderId = folderId)) },
                            )
                        }
                    }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PhoneSetupBody(overlay: Overlay.PhoneSetup, active: Boolean, close: () -> Unit) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var status by remember { mutableStateOf("Starting…") }
    val server = remember {
        PhoneSetupServer(overlay.title, overlay.fields) { values ->
            status = "Received ✓"
            overlay.onSubmit(values)
            kotlinx.coroutines.delay(900)
            close()
        }
    }
    DisposableEffect(server) {
        scope.launch {
            val u = server.start(scope)
            url = u
            qr = u?.let { PhoneSetupServer.qr(it) }
            status = if (u == null) "Connect the TV to Wi-Fi or Ethernet to use your phone." else "Waiting for your phone…"
        }
        onDispose { server.stop() }
    }
    MenuList(active) { first ->
        PanelTitle(overlay.title)
        Hint("Scan with your phone's camera, fill in the form, and tap Send. Your phone must be on the same network.")
        qr?.let {
            Box(
                Modifier
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .size(210.dp)
                    .background(Color.White, Shapes.row)
                    .padding(12.dp),
            ) {
                Image(it, "QR code for $url", filterQuality = androidx.compose.ui.graphics.FilterQuality.None, modifier = Modifier.fillMaxSize())
            }
        }
        url?.let { Text(it, style = Type.caption, color = palette.secondary, modifier = Modifier.padding(horizontal = 18.dp)) }
        Text(status, style = Type.body, color = palette.primary, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp).testTag("phone-status"))
        MenuRow("Done", close, Modifier.focusRequester(first))
    }
}

/** One-time orientation for the remote: where settings live and how to edit apps. */
@Composable
private fun TipsCard(active: Boolean, dismiss: () -> Unit) {
    val palette = LocalPalette.current
    val first = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) { withFrameNanos { }; runCatching { first.requestFocus() } } }
    Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.Center) {
        GlassBoxCard(Modifier.width(620.dp)) {
            Column(Modifier.padding(36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Welcome to Glass", style = Type.title, color = palette.primary)
                Tip("☰", "Press Menu, or hold Select, on any app to move it, hide it, change its icon or put it in a folder.")
                Tip("⚙", "Settings are at the top right. Press Up from the top row.")
                Tip("▶", "Press Play/Pause on an app or a featured title to open it straight away.")
                Tip("📱", "Long text like API keys can be typed on your phone. Look for \"Type on Your Phone\".")
                Spacer(Modifier.height(6.dp))
                MenuRow("Get Started", dismiss, Modifier.focusRequester(first))
            }
        }
    }
}

@Composable
private fun Tip(symbol: String, text: String) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(symbol, style = Type.heading, color = palette.primary, modifier = Modifier.width(30.dp))
        Text(text, style = Type.secondary, color = palette.secondary)
    }
}

@Composable
private fun GlassBoxCard(modifier: Modifier, content: @Composable () -> Unit) {
    dev.glasslauncher.ui.GlassBox(modifier = modifier, shape = Shapes.panel) { content() }
}
