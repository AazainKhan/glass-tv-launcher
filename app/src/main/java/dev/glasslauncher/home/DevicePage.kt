package dev.glasslauncher.home

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import dev.glasslauncher.R
import dev.glasslauncher.system.RemoteAction
import dev.glasslauncher.data.ControllerSticks
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.MappingProfiles
import dev.glasslauncher.data.ProfileKind
import dev.glasslauncher.data.StickTuning
import dev.glasslauncher.system.ControllerList
import dev.glasslauncher.system.RemoteButton
import dev.glasslauncher.system.RemoteButtons
import dev.glasslauncher.system.RemoteList
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Type
import dev.glasslauncher.ui.isSelectKey

/** What a device page is for: a game controller (sticks, buttons) or a remote (its buttons). */
enum class PageKind { Gamepad, Remote }

/** The names of the devices of [kind] that are connected now. */
internal fun deviceNames(kind: PageKind, context: android.content.Context): List<String> = when (kind) {
    PageKind.Gamepad -> ControllerList.read(context).map { it.name }
    PageKind.Remote -> RemoteList.read(context).map { it.name }
}.distinct()

/** The stick tuning of [controller] (defaults when never changed). */
internal fun LauncherConfig.sticksOf(controller: String): ControllerSticks = controllerSticks[controller] ?: ControllerSticks()

/** A stick's tuning changed: the one new value of the config, for [controller]. */
internal fun LauncherConfig.withStick(controller: String, right: Boolean, change: (StickTuning) -> StickTuning): LauncherConfig {
    val cur = sticksOf(controller)
    val next = if (right) cur.copy(right = change(cur.right)) else cur.copy(left = change(cur.left))
    return copy(controllerSticks = controllerSticks + (controller to next))
}

/** A value stepped by [delta] inside [lo]..[hi], rounded to the step so repeated presses don't drift. */
internal fun stepped(value: Float, delta: Float, lo: Float, hi: Float): Float = ((value + delta).coerceIn(lo, hi) * 1000f).toInt() / 1000f



/** One thing the editor lists: a mappable button, or one stick's tuning. [id] is the button's id (`pad:a`, `app1`) or `stick:left` / `stick:right`. */
internal data class Slot(val id: String, val name: String) {
    val isStick get() = id.startsWith("stick:")
}

/** What the editor lists for a drawing, with the names that device's own buttons carry (PlayStation or Xbox words), and which side of the drawing each callout sits on. */
internal object EditorSlots {
    /** Left column, top to bottom, then right column, in the order of their buttons on the drawing. */
    fun left(style: PadStyle) = OutlineLayout.ordered(style).first
    fun right(style: PadStyle) = OutlineLayout.ordered(style).second

    fun of(style: PadStyle): List<Slot> = (left(style) + right(style)).map { Slot(it, nameOf(it, style)) }

    fun nameOf(id: String, style: PadStyle): String {
        val ps = style == PadStyle.PlayStation
        return when (id) {
            "pad:a" -> if (ps) "Cross" else "A"; "pad:b" -> if (ps) "Circle" else "B"; "pad:x" -> if (ps) "Square" else "X"; "pad:y" -> if (ps) "Triangle" else "Y"
            "pad:l1" -> if (ps) "L1" else "Left Bumper"; "pad:r1" -> if (ps) "R1" else "Right Bumper"
            "pad:l2" -> if (ps) "L2" else "Left Trigger"; "pad:r2" -> if (ps) "R2" else "Right Trigger"
            "pad:select" -> if (ps) "Create" else "View"; "pad:start" -> if (ps) "Options" else "Menu"; "pad:mode" -> if (ps) "PS Button" else "Guide"
            "pad:thumbl" -> "L3"; "pad:thumbr" -> "R3"; "stick:left" -> "Left Stick"; "stick:right" -> "Right Stick"
            else -> RemoteButtons.find(id)?.label ?: id
        }
    }

    /** The config key a slot's mapping is stored under for [device]: a controller's own button key, or the remote's shared id. */
    fun keyOf(kind: PageKind, device: String, slot: Slot) = if (kind == PageKind.Gamepad) RemoteButtons.padKey(device, slot.id.removePrefix("pad:")) else slot.id

    /** The pad's own name for a button, as [PadState.pressed] spells it ("A", "LB"); a remote's button id lit by [RemoteKeys.idFor]. */
    fun litName(slot: Slot) = if (slot.id.startsWith("pad:")) PadLabels.shortName(slot.id.removePrefix("pad:")) else slot.id
}

/** The remote's drawn button a key press belongs to; the editor lights its callout while the key is held. */
object RemoteKeys {
    fun idFor(code: Int): String? = when (code) {
        KeyEvent.KEYCODE_BUTTON_9 -> "app1"; KeyEvent.KEYCODE_BUTTON_10 -> "app2"
        KeyEvent.KEYCODE_BUTTON_11 -> "app3"; KeyEvent.KEYCODE_BUTTON_12 -> "app4"
        KeyEvent.KEYCODE_BUTTON_13, KeyEvent.KEYCODE_APP_SWITCH -> "recents"
        else -> null
    }
}

/** What the page was last on, so coming back from the app chooser lands on the same button. Process-wide and tiny. */
private object EditorMemory { var slot: String? = null }

private fun vendorOf(name: String): Int? = runCatching {
    InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }.firstOrNull { it.name == name }?.vendorId
}.getOrNull()


/**
 * The mapping editor (P233): a centred line drawing of the controller (or the Fire remote), each button with a callout beside it saying what
 * it does now (blue once the user mapped it), the real glyphs on them (PlayStation symbols or Xbox letters). A button lights on the
 * drawing while it is held, so it doubles as the tester. Select on a callout opens the action picker (or a stick's tuning) as a sheet;
 * Back closes it. The profile bar sits under the drawing. Keys are watched, never taken, so the page stays navigable.
 */
@Composable
internal fun DevicePage(
    kind: PageKind,
    startName: String,
    cfg: LauncherConfig,
    edit: ((LauncherConfig) -> LauncherConfig) -> Unit,
    active: Boolean,
    openApp: (String) -> Unit,
) {
    val sink = LocalTitleSink.current
    val pageKey = LocalPageKey.current
    DisposableEffect(sink, pageKey) {
        sink?.wide = pageKey
        onDispose { if (sink != null && sink.wide == pageKey) sink.wide = null }
    }
    PanelTitle(if (kind == PageKind.Gamepad) "Game Controller" else "Remote")
    val context = LocalContext.current
    var names by remember { mutableStateOf(deviceNames(kind, context)) }
    LaunchedEffect(kind) { while (true) { kotlinx.coroutines.delay(3_000); names = deviceNames(kind, context) } }
    var selected by remember { mutableStateOf(startName) }
    val name = if (names.isEmpty() || selected in names) selected else names.first()
    var pad by remember { mutableStateOf(PadState()) }
    var lit by remember { mutableStateOf(emptySet<String>()) }
    val view = LocalView.current
    DisposableEffect(view) {
        view.setOnGenericMotionListener { _, e ->
            if (e.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) { pad = pad.apply(e); true } else false
        }
        onDispose { view.setOnGenericMotionListener(null) }
    }
    val style = remember(kind, name) { if (kind == PageKind.Remote) PadStyle.Remote else PadStyle.of(name, vendorOf(name)) }
    val slots = remember(style) { EditorSlots.of(style) }
    var sheet by remember { mutableStateOf<Slot?>(null) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    val requesters = remember(style) { slots.associate { it.id to FocusRequester() } }
    val sheetFirst = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) { kotlinx.coroutines.delay(16); runCatching { (requesters[EditorMemory.slot] ?: requesters.getValue(slots.first().id)).requestFocus() } }
    }
    // The sheet takes focus when it opens and gives it back to the callout it came from.
    LaunchedEffect(sheet) {
        if (sheet != null) { kotlinx.coroutines.delay(16); runCatching { sheetFirst.requestFocus() } }
        else if (wasOpen) runCatching { requesters[focusedId]?.requestFocus() }
        wasOpen = sheet != null
    }
    androidx.activity.compose.BackHandler(enabled = active && sheet != null) { sheet = null }
    val palette = LocalPalette.current
    val open = sheet == null
    Box(
        Modifier.fillMaxSize().testTag("device-page")
            .onPreviewKeyEvent { e ->
                val code = e.key.nativeKeyCode
                val down = when (e.type) { KeyEventType.KeyDown -> true; KeyEventType.KeyUp -> false; else -> return@onPreviewKeyEvent false }
                // Fire's fallback keys (L2 -> DPAD_CENTER and the like) are the system's guess, not a press.
                if (e.nativeKeyEvent.flags and android.view.KeyEvent.FLAG_FALLBACK != 0) return@onPreviewKeyEvent false
                if (kind == PageKind.Gamepad) pad = pad.key(code, down)
                else RemoteKeys.idFor(code)?.let { id -> lit = if (down) lit + id else lit - id }
                false
            },
    ) {
        // Connected: <name> (Select cycles through the connected ones).
        Row(Modifier.align(Alignment.TopCenter).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connected:", style = Type.secondary, color = palette.secondary)
            DevPill(
                if (names.size > 1) "$name  ⇅" else name.ifEmpty { "No device" }, Modifier.testTag("device-switch"), enabled = open,
                onClick = { if (names.size > 1) selected = names[(names.indexOf(name) + 1) % names.size] },
            ) { fg -> Text(if (names.size > 1) "$name  ⇅" else name.ifEmpty { "No device" }, style = Type.body, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        val heldIds = slots.filter { s -> EditorSlots.litName(s).let { n -> n in pad.pressed || n in lit } }.map { it.id }.toSet()
        val mappedIds = slots.filter { !it.isStick && isMapped(kind, name, it, cfg) }.map { it.id }.toSet()
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(top = 40.dp, bottom = 84.dp).graphicsLayer { alpha = if (open) 1f else 0.3f }) {
            val ow = OutlineGeometry.size(style)
            val lay = OutlineLayout.compute(style, maxWidth.value, maxHeight.value)
            val ox = lay.ox.dp; val oy = lay.oy.dp
            val ink = palette.primary; val accent = palette.accent
            Canvas(Modifier.fillMaxSize()) {
                for ((id, ld) in lay.leaders) {
                    val c = when { id in heldIds -> OutlineColors.held(palette.light); id == focusedId -> ink; id in mappedIds -> accent.copy(alpha = 0.85f); else -> OutlineColors.stroke(palette.light).copy(alpha = 0.85f) }
                    val w = (if (id == focusedId || id in heldIds) 1.4f else 0.8f).dp.toPx()
                    for (k in 0 until ld.points.lastIndex) drawLine(c, Offset(ld.points[k].x.dp.toPx(), ld.points[k].y.dp.toPx()), Offset(ld.points[k + 1].x.dp.toPx(), ld.points[k + 1].y.dp.toPx()), strokeWidth = w)
                }
            }
            PadOutline(style, OutlineState(heldIds, mappedIds, focusedId, pad), OutlineColors.stroke(palette.light), OutlineColors.held(palette.light), accent, ink, Modifier.offset(ox, oy).size(ow.x.dp, ow.y.dp).testTag("pad-outline"))
            for (slot in slots) {
                val ld = lay.leaders.getValue(slot.id)
                val text = if (slot.isStick) cfg.sticksOf(name).let { s -> val t = if (slot.id == "stick:right") s.right else s.left; "Stick ${"%.2f".format(t.deadzoneX)} · ${"%.1f".format(t.sensX)}×" }
                    else actionOf(kind, name, slot, cfg).let { if (it == RemoteAction.Default) "Default" else dev.glasslauncher.settings.actionLabel(it, context.packageManager) }
                Callout(
                    slot, style, text, slot.id in mappedIds, slot.id in heldIds, open, requesters.getValue(slot.id),
                    Modifier.offset((if (ld.onLeft) lay.leftX else lay.rightX).dp, (ld.rowY - OutlineLayout.CALLOUT_H / 2).dp),
                    onFocus = { focusedId = slot.id; EditorMemory.slot = slot.id }, onClick = { sheet = slot },
                )
            }
        }
        if (name.isNotEmpty()) {
            // Above Settings' 36 dp bottom fade, or the bar reads as disabled.
            ProfileBar(if (kind == PageKind.Gamepad) ProfileKind.Pad else ProfileKind.Remote, name, cfg, edit, open, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
        }
        sheet?.let { s ->
            Box(Modifier.fillMaxSize().testTag("picker-sheet"), contentAlignment = Alignment.Center) {
                val panel = Modifier.width(420.dp).height(330.dp).shadow(24.dp, RoundedCornerShape(18.dp))
                if (s.isStick) StickPane(name, s.id == "stick:right", pad, cfg, edit, sheetFirst, panel)
                else ActionPane(kind, style, name, s, cfg, edit, { id -> sheet = null; openApp(id) }, sheetFirst, { sheet = null }, panel)
            }
        }
    }
}

/** What a button does now: its own mapping (a controller's own key first), else the default. */
private fun actionOf(kind: PageKind, device: String, slot: Slot, cfg: LauncherConfig): RemoteAction {
    val button = RemoteButtons.find(EditorSlots.keyOf(kind, device, slot)) ?: return RemoteAction.Default
    return cfg.remoteButtons[button.id]?.let(RemoteAction::parse)?.takeIf { it != RemoteAction.Default }
        ?: if (kind == PageKind.Gamepad) RemoteButtons.action(RemoteButtons.find(slot.id)!!, cfg.remoteButtons) else RemoteButtons.action(button, cfg.remoteButtons)
}

/** Mapped: the user gave this button something. For a remote the app buttons always do something, so only a changed one counts. */
private fun isMapped(kind: PageKind, device: String, slot: Slot, cfg: LauncherConfig): Boolean =
    if (slot.isStick) false
    else if (kind == PageKind.Gamepad) actionOf(kind, device, slot, cfg) != RemoteAction.Default
    else cfg.remoteButtons.containsKey(slot.id)

/** One button's callout: its glyph and what it does (dim on the default, blue once mapped). Select opens the picker. */
@Composable
private fun Callout(
    slot: Slot, style: PadStyle, text: String, mapped: Boolean, held: Boolean, enabled: Boolean, requester: FocusRequester,
    modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val pressed = remember { booleanArrayOf(false) }
    val fg = if (focused) palette.onFocusFill else palette.primary
    Row(
        modifier.width(OutlineLayout.CALLOUT_W.dp).height(OutlineLayout.CALLOUT_H.dp)
            .focusRequester(requester)
            .background(if (focused) palette.focusFill else if (held) palette.primary.copy(alpha = 0.34f) else palette.rowFill, RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { e ->
                if (!isSelectKey(e.key.nativeKeyCode)) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) pressed[0] = true
                if (e.type == KeyEventType.KeyUp && pressed[0]) { pressed[0] = false; onClick() }
                true
            }
            .semantics { role = Role.Button; contentDescription = "${slot.name}, $text" }
            .testTag("callout:${slot.id}")
            .focusProperties { canFocus = enabled }
            .focusable().padding(start = 5.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlyphChip(slot, style, 22.dp, mapped, focused)
        Text(text, style = Type.secondary, color = if (mapped) (if (focused) palette.onFocusFill else palette.accent) else fg.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** The text on a button's glyph, or null when it is a drawn symbol or an icon. */
private fun glyphText(slot: Slot, style: PadStyle): String? {
    val ps = style == PadStyle.PlayStation
    return when (slot.id) {
        "pad:a", "pad:b", "pad:x", "pad:y" -> if (ps) null else slot.id.removePrefix("pad:").uppercase()
        "pad:l1" -> if (ps) "L1" else "LB"; "pad:r1" -> if (ps) "R1" else "RB"; "pad:l2" -> if (ps) "L2" else "LT"; "pad:r2" -> if (ps) "R2" else "RT"
        "pad:select" -> if (ps) "Create" else "View"; "pad:start" -> if (ps) "Options" else "Menu"
        "pad:mode" -> if (ps) "PS" else null
        "pad:thumbl" -> "L3"; "pad:thumbr" -> "R3"; "stick:left" -> "L"; "stick:right" -> "R"
        "recents", "settings" -> null
        else -> slot.id.removePrefix("app")
    }
}

/** A button's picture: the PlayStation symbol or the letter on a circle for the face buttons, a capsule for the rest. A mapped one is filled. */
@Composable
private fun GlyphChip(slot: Slot, style: PadStyle, size: Dp, filled: Boolean, onFocusFill: Boolean) {
    val palette = LocalPalette.current
    val ink = if (onFocusFill) palette.onFocusFill else palette.primary
    val round = slot.id in setOf("pad:a", "pad:b", "pad:x", "pad:y", "stick:left", "stick:right") || slot.id.startsWith("app")
    val shape = if (round) CircleShape else RoundedCornerShape(size / 3)
    val text = glyphText(slot, style)
    Box(
        Modifier.defaultMinSize(size, size).height(size)
            .background(if (filled) ink.copy(alpha = 0.22f) else Color.Transparent, shape)
            .border(1.5.dp, ink.copy(alpha = if (filled) 0.85f else 0.45f), shape)
            .padding(horizontal = if (round) 0.dp else 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            text != null -> Text(text, style = Type.caption.copy(fontSize = if (text.length > 3) (size.value * 0.42f).sp else (size.value * 0.5f).sp, fontWeight = FontWeight.Bold), color = ink, maxLines = 1)
            slot.id.startsWith("pad:") && slot.id != "pad:mode" -> SymbolMark(slot.id.removePrefix("pad:"), size, ink)
            else -> Image(
                painterResource(when (slot.id) { "recents" -> R.drawable.ic_app_switcher; "pad:mode" -> R.drawable.ic_home; else -> R.drawable.ic_settings }),
                null, colorFilter = ColorFilter.tint(ink), modifier = Modifier.size(size * 0.6f),
            )
        }
    }
}

/** The focused button's action picker: what it does now, and every choice; Select on one sets it. */
@Composable
private fun ActionPane(
    kind: PageKind, style: PadStyle, device: String, slot: Slot, cfg: LauncherConfig, edit: ((LauncherConfig) -> LauncherConfig) -> Unit,
    openApp: (String) -> Unit, firstRow: FocusRequester, onDone: () -> Unit, modifier: Modifier,
) {
    val palette = LocalPalette.current
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val key = EditorSlots.keyOf(kind, device, slot)
    val button = RemoteButtons.find(key) ?: return
    val current = actionOf(kind, device, slot, cfg)
    val dflt = RemoteButtons.defaultFor(button)
    LaunchedEffect(Unit) { dev.glasslauncher.addons.AddonHost.loadActions(ctx, dev.glasslauncher.addons.AddonSnapshot(cfg.addonsEnabled, cfg.addonSettings)) }
    fun set(a: RemoteAction) = edit { it.copy(remoteButtons = it.remoteButtons + (key to a.key)) }
    fun label(a: RemoteAction) = if (a == RemoteAction.Default) "Default" else dev.glasslauncher.settings.actionLabel(a, pm)
    Column(modifier.background((if (palette.light) Color(0xFFF4F5F8) else Color(0xFF23252C)), RoundedCornerShape(18.dp)).padding(12.dp).testTag("action-pane"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlyphChip(slot, style, 36.dp, isMapped(kind, device, slot, cfg), false)
            Column {
                Text(slot.name, style = Type.heading, color = palette.primary, maxLines = 1)
                Text("Does: ${label(current)}", style = Type.secondary, color = palette.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("action-current"))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val options = listOf(dflt, RemoteAction.TvSettings, RemoteAction.ControlCenter, RemoteAction.AppSwitcher, RemoteAction.Home, RemoteAction.Nothing).distinct()
            options.forEachIndexed { i, a ->
                Option(if (a == dflt && a != RemoteAction.Default) "${label(a)} (Default)" else label(a), a == current, if (i == 0) Modifier.focusRequester(firstRow) else Modifier, "option:${a.key}") { set(a); onDone() }
            }
            dev.glasslauncher.addons.AddonActionPicker.entries(dev.glasslauncher.addons.AddonHost.actionLists, current.key).forEach { e ->
                Option(e.label, e.key == current.key, Modifier, "option:${e.key}") { if (e.available) { set(RemoteAction.parse(e.key)); onDone() } }
            }
            Option("Open Another App…" + ((current as? RemoteAction.OpenApp)?.takeIf { it != dflt }?.let { " (${label(it)})" } ?: ""), false, Modifier, "option:app") { openApp(key) }
        }
    }
}

/** One choice in the picker: a capsule, ticked when it is the current action. */
@Composable
private fun Option(text: String, ticked: Boolean, modifier: Modifier, tag: String, onClick: () -> Unit) {
    DevPill(text, modifier.fillMaxWidth().testTag(tag), selected = ticked, onClick = onClick) { fg ->
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(text, style = Type.body, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (ticked) Text("✓", style = Type.body, color = fg)
        }
    }
}

/** A stick's dead zone and sensitivity: the live circle, the X and Y sliders and Reset. */
@Composable
private fun StickPane(controller: String, right: Boolean, pad: PadState, cfg: LauncherConfig, edit: ((LauncherConfig) -> LauncherConfig) -> Unit, first: FocusRequester, modifier: Modifier) {
    val palette = LocalPalette.current
    var tab by remember { mutableStateOf(0) }
    val tuning = cfg.sticksOf(controller).let { if (right) it.right else it.left }
    val ink = palette.primary
    Column(modifier.background((if (palette.light) Color(0xFFF4F5F8) else Color(0xFF23252C)), RoundedCornerShape(18.dp)).padding(12.dp).testTag("stick-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (right) "Right Stick" else "Left Stick", style = Type.heading, color = ink, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Deadzone", "Sensitivity").forEachIndexed { i, t -> DevPill(t, (if (i == 0) Modifier.focusRequester(first) else Modifier).testTag("stick-tab:$t"), selected = tab == i, onClick = { tab = i }) { fg -> Text(t, style = Type.secondary, color = fg, maxLines = 1) } }
        }
        // The live circle: the dead zone as its inner ring, the dot following the stick (scaled by sensitivity on the second tab).
        val sx = if (right) pad.rightX else pad.leftX; val sy = if (right) pad.rightY else pad.leftY
        Canvas(Modifier.align(Alignment.CenterHorizontally).size(104.dp).testTag("stick-circle")) {
            val r = size.minDimension / 2 - 2.dp.toPx(); val c = center
            drawCircle(ink.copy(alpha = 0.10f), r, c); drawCircle(ink.copy(alpha = 0.40f), r, c, style = Stroke(1.5.dp.toPx()))
            drawLine(ink.copy(alpha = 0.18f), Offset(c.x - r, c.y), Offset(c.x + r, c.y)); drawLine(ink.copy(alpha = 0.18f), Offset(c.x, c.y - r), Offset(c.x, c.y + r))
            val dz = Offset(tuning.deadzoneX * 2f * r, tuning.deadzoneY * 2f * r)
            drawOval(ink.copy(alpha = 0.55f), Offset(c.x - dz.x, c.y - dz.y), Size(dz.x * 2, dz.y * 2), style = Stroke(1.5.dp.toPx()))
            val k = if (tab == 1) Offset(tuning.sensX, tuning.sensY) else Offset(1f, 1f)
            drawCircle(ink, 5.dp.toPx(), Offset(c.x + (sx * k.x).coerceIn(-1f, 1f) * r, c.y + (sy * k.y).coerceIn(-1f, 1f) * r))
        }
        val dead = tab == 0
        val lo = if (dead) 0f else StickTuning.SENS_MIN; val hi = if (dead) StickTuning.DEADZONE_MAX else StickTuning.SENS_MAX
        val step = if (dead) 0.01f else 0.05f
        fun get(x: Boolean) = if (dead) (if (x) tuning.deadzoneX else tuning.deadzoneY) else (if (x) tuning.sensX else tuning.sensY)
        // A press changes the value relative to the newest config, so fast repeats never write from a stale value.
        fun nudge(x: Boolean, dir: Int) = edit { c -> c.withStick(controller, right) { t ->
            if (dead) (if (x) t.copy(deadzoneX = stepped(t.deadzoneX, dir * step, lo, hi)) else t.copy(deadzoneY = stepped(t.deadzoneY, dir * step, lo, hi)))
            else (if (x) t.copy(sensX = stepped(t.sensX, dir * step, lo, hi)) else t.copy(sensY = stepped(t.sensY, dir * step, lo, hi)))
        } }
        StickSlider("X", get(true), lo, hi, Modifier.testTag("stick-slider:x")) { nudge(true, it) }
        StickSlider("Y", get(false), lo, hi, Modifier.testTag("stick-slider:y")) { nudge(false, it) }
        DevPill("Reset to Default", Modifier.fillMaxWidth().testTag("stick-reset"), onClick = { edit { c -> c.withStick(controller, right) { StickTuning() } } }) { fg -> Text("Reset to Default", style = Type.body, color = fg, maxLines = 1) }
    }
}

/**
 * Saved mappings (P233): the first pill steps through them, loading each onto this device; Save keeps the current mapping under the loaded
 * profile's name; Save As… asks for a name with the system keyboard (default "Profile N"); Delete drops the loaded one. A mapping changed
 * after loading reads "(changed)" until saved.
 */
@Composable
private fun ProfileBar(kind: ProfileKind, device: String, cfg: LauncherConfig, edit: ((LauncherConfig) -> LauncherConfig) -> Unit, enabled: Boolean, modifier: Modifier) {
    val palette = LocalPalette.current
    val profiles = MappingProfiles.of(cfg, kind)
    val active = cfg.activeProfile[device]?.let { n -> profiles.firstOrNull { it.name == n } }
    val dirty = active != null && MappingProfiles.capture(cfg, kind, device, active.name) != active
    val label = when { active == null -> "Custom"; dirty -> "${active.name} (changed)"; else -> active.name }
    var naming by remember { mutableStateOf(false) }
    val free = generateSequence(1) { it + 1 }.map { "Profile $it" }.first { n -> profiles.none { it.name == n } }
    // Empty on open, so typing is the name; left empty, Done keeps the default shown faintly behind it.
    var name by remember(naming) { mutableStateOf("") }
    val nameFocus = remember { FocusRequester() }
    val saveAs = remember { FocusRequester() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Profile:", style = Type.secondary, color = palette.secondary)
        if (naming) {
            LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }
            Box(Modifier.width(260.dp).height(36.dp).background(palette.primary.copy(alpha = 0.14f), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (name.isEmpty()) Text(free, style = Type.heading, color = palette.faint)
                dev.glasslauncher.home.SystemTextField(
                    name, { name = it.take(MappingProfiles.MAX_NAME) }, nameFocus,
                    onFocusLost = { naming = false },
                    onBack = { naming = false; runCatching { saveAs.requestFocus() } },
                    onAction = { edit { c -> MappingProfiles.save(c, kind, device, name.ifBlank { free }) ?: c }; naming = false },
                    modifier = Modifier.testTag("profile-name"), label = "Profile name",
                )
            }
        } else {
            val next = if (profiles.isEmpty()) null else profiles[((profiles.indexOfFirst { it.name == active?.name }) + 1) % profiles.size]
            DevPill(label, Modifier.testTag("profile-pick"), enabled = enabled, onClick = { next?.let { p -> edit { c -> MappingProfiles.load(c, kind, device, p) } } }) { fg ->
                Text(if (profiles.size > 1) "$label  ⇅" else label, style = Type.body, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (dirty) DevPill("Save", Modifier.testTag("profile-save"), enabled = enabled, onClick = { edit { c -> MappingProfiles.save(c, kind, device, active!!.name) ?: c } }) { fg -> Text("Save", style = Type.body, color = fg, maxLines = 1) }
            DevPill("Save As…", Modifier.focusRequester(saveAs).testTag("profile-saveas"), enabled = enabled, onClick = { naming = true }) { fg -> Text("Save As…", style = Type.body, color = fg, maxLines = 1) }
            if (active != null) DevPill("Delete", Modifier.testTag("profile-delete"), enabled = enabled, onClick = { edit { c -> MappingProfiles.delete(c, kind, active.name) } }) { fg -> Text("Delete", style = Type.body, color = fg, maxLines = 1) }
        }
    }
}

/** A slider the D-pad moves: Left and Right nudge it one step (the caller decides how far); it shows [value]. */
@Composable
private fun StickSlider(label: String, value: Float, lo: Float, hi: Float, modifier: Modifier, onNudge: (Int) -> Unit) {
    val ink = LocalPalette.current.primary
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(32.dp)
            .background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { onNudge(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { onNudge(1); true }
                    else -> false
                }
            }
            .semantics { role = Role.Button; contentDescription = "$label ${"%.2f".format(value)}" }
            .focusable().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val fg = if (focused) Color.Black else ink
        Text(label, style = Type.secondary, color = fg)
        Canvas(Modifier.weight(1f).height(14.dp)) {
            val y = size.height / 2; val t = ((value - lo) / (hi - lo)).coerceIn(0f, 1f)
            drawLine(fg.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx())
            drawCircle(fg, 6.dp.toPx(), Offset(t * size.width, y))
        }
        Text("%.2f".format(value), style = Type.secondary, color = fg)
    }
}


// ── shared bits ──────────────────────────────────────────────────────────────

// ── shared bits ──────────────────────────────────────────────────────────────

/** A focusable capsule: translucent at rest, white (dark text) when focused; [selected] lights it lightly. */
@Composable
private fun DevPill(text: String, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit, content: @Composable (Color) -> Unit) {
    val ink = LocalPalette.current.primary
    var focused by remember { mutableStateOf(false) }
    val pressed = remember { booleanArrayOf(false) }
    Box(
        modifier.height(32.dp)
            .background(if (focused) Color.White else ink.copy(alpha = if (selected) 0.30f else 0.12f), RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (!isSelectKey(e.key.nativeKeyCode)) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) pressed[0] = true
                if (e.type == KeyEventType.KeyUp && pressed[0]) { pressed[0] = false; onClick() }
                true
            }
            .semantics { role = Role.Button; contentDescription = text }
            .focusProperties { canFocus = enabled }
            .focusable().padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) { content(if (focused) Color.Black else ink) }
}


