package dev.glasslauncher.home

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import dev.glasslauncher.data.ControllerSticks
import dev.glasslauncher.data.LauncherConfig
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

/**
 * Where each part of the drawn gamepad is, in a [PadGeometry.W] x [PadGeometry.H] dp box (P186). Pure data, so the leader lines and the
 * layout test read the same numbers the drawing uses. The art is Glass's own simple vector drawing.
 */
object PadGeometry {
    const val W = 360f
    const val H = 270f
    /** Anchor of each button by its [PadLabels] name: where its leader line starts. */
    val anchors: Map<String, Offset> = mapOf(
        "LT" to Offset(90f, 13f), "RT" to Offset(270f, 13f), "LB" to Offset(88f, 32f), "RB" to Offset(272f, 32f),
        "Y" to Offset(265f, 65f), "X" to Offset(235f, 95f), "B" to Offset(295f, 95f), "A" to Offset(265f, 125f),
        "Guide" to Offset(180f, 70f), "Select" to Offset(152f, 100f), "Start" to Offset(208f, 100f),
        "L3" to Offset(135f, 160f), "R3" to Offset(225f, 160f),
    )
    val dpad = Offset(95f, 95f)
    val leftStick = Offset(135f, 160f)
    val rightStick = Offset(225f, 160f)
    const val STICK_R = 24f
    /** Right column (top to bottom) and the bottom row (left to right), by name. */
    val rightColumn = listOf("RT", "RB", "Y", "X", "B", "A", "Start", "R3")
    val bottomRow = listOf("LT", "LB", "Select", "Guide", "L3")
}

/** The remote's drawn buttons, by id, in a [RemoteGeometry.W] x [RemoteGeometry.H] dp box. */
object RemoteGeometry {
    const val W = 140f
    const val H = 270f
    val anchors: Map<String, Offset> = mapOf(
        "up" to Offset(70f, 28f), "down" to Offset(70f, 84f), "left" to Offset(42f, 56f), "right" to Offset(98f, 56f), "select" to Offset(70f, 56f),
        "back" to Offset(36f, 112f), "home" to Offset(104f, 112f), "menu" to Offset(70f, 126f),
        "rewind" to Offset(36f, 150f), "playpause" to Offset(70f, 150f), "ff" to Offset(104f, 150f),
        "app1" to Offset(36f, 190f), "app2" to Offset(104f, 190f), "app3" to Offset(36f, 226f), "app4" to Offset(104f, 226f),
        "recents" to Offset(70f, 190f), "settings" to Offset(70f, 226f),
    )

    /** The drawn button a key press lights up. */
    fun idForKey(code: Int): String? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> "up"; KeyEvent.KEYCODE_DPAD_DOWN -> "down"; KeyEvent.KEYCODE_DPAD_LEFT -> "left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "select"
        KeyEvent.KEYCODE_BACK -> "back"; KeyEvent.KEYCODE_HOME -> "home"; KeyEvent.KEYCODE_MENU -> "menu"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "rewind"; KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "playpause"; KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "ff"
        KeyEvent.KEYCODE_BUTTON_9 -> "app1"; KeyEvent.KEYCODE_BUTTON_10 -> "app2"
        KeyEvent.KEYCODE_BUTTON_11 -> "app3"; KeyEvent.KEYCODE_BUTTON_12 -> "app4"
        KeyEvent.KEYCODE_BUTTON_13, KeyEvent.KEYCODE_APP_SWITCH -> "recents"
        KeyEvent.KEYCODE_UNKNOWN -> null
        else -> null
    }
}

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

/**
 * A game controller's or a remote's page (P186): a drawing of the device with leader lines from each button to what it does now, the
 * pressed buttons lit live (it is the tester), and for a controller the sticks' dead zone and sensitivity. Select on a button's label
 * opens its mapping. Keys are watched, never taken: the page stays navigable while they light up.
 */
@Composable
internal fun DevicePage(
    kind: PageKind,
    startName: String,
    cfg: LauncherConfig,
    edit: ((LauncherConfig) -> LauncherConfig) -> Unit,
    active: Boolean,
    openMapping: (String) -> Unit,
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
    val first = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) { kotlinx.coroutines.delay(16); runCatching { first.requestFocus() } } }
    val palette = LocalPalette.current
    Box(
        Modifier.fillMaxSize().testTag("device-page")
            .onPreviewKeyEvent { e ->
                val code = e.key.nativeKeyCode
                val down = when (e.type) { KeyEventType.KeyDown -> true; KeyEventType.KeyUp -> false; else -> return@onPreviewKeyEvent false }
                // Fire turns a pad button nothing consumed into a fallback key (L2/R2 -> DPAD_CENTER, Start -> Home): not a button the user pressed.
                if (e.nativeKeyEvent.flags and android.view.KeyEvent.FLAG_FALLBACK != 0) return@onPreviewKeyEvent false
                if (kind == PageKind.Gamepad) pad = pad.key(code, down)
                else RemoteGeometry.idForKey(code)?.let { id -> lit = if (down) lit + id else lit - id }
                false
            },
    ) {
        // Connected: <name> (Select cycles through the connected ones).
        Row(Modifier.align(Alignment.TopCenter).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connected:", style = Type.secondary, color = palette.secondary)
            DevPill(
                if (names.size > 1) "$name  ⇅" else name.ifEmpty { "No device" }, Modifier.focusRequester(first).testTag("device-switch"),
                onClick = { if (names.size > 1) selected = names[(names.indexOf(name) + 1) % names.size] },
            ) { fg -> Text(if (names.size > 1) "$name  ⇅" else name.ifEmpty { "No device" }, style = Type.body, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (kind == PageKind.Gamepad) GamepadBody(name, pad, cfg, edit, openMapping)
        else RemoteBody(name, lit, cfg, edit, openMapping)
    }
}

// ── controller ───────────────────────────────────────────────────────────────

private val CANVAS_X = 262.dp
private val CANVAS_Y = 62.dp
private val RIGHT_X = 655.dp
private val RIGHT_W = 215.dp
private const val ROW_PITCH = 44f
private const val ROW_Y0 = 50f
private const val BOTTOM_Y = 352f
private const val BOTTOM_PITCH = 72f

@Composable
private fun GamepadBody(controller: String, pad: PadState, cfg: LauncherConfig, edit: ((LauncherConfig) -> LauncherConfig) -> Unit, openMapping: (String) -> Unit) {
    val palette = LocalPalette.current
    val ink = palette.primary
    val pm = LocalContext.current.packageManager
    val buttons = RemoteButtons.pad.associateBy { PadLabels.shortName(it.id.removePrefix("pad:")) }
    fun actionText(b: RemoteButton): String {
        val own = cfg.remoteButtons[RemoteButtons.padKey(controller, b.id.removePrefix("pad:"))]
        val a = if (own != null) dev.glasslauncher.system.RemoteAction.parse(own) else RemoteButtons.action(b, cfg.remoteButtons)
        return if (a == dev.glasslauncher.system.RemoteAction.Default) "Default" else dev.glasslauncher.settings.actionLabel(a, pm)
    }
    // The leader lines and the drawing share one canvas over the page; the labels sit on top of it.
    Canvas(Modifier.fillMaxSize()) {
        val ox = CANVAS_X.toPx(); val oy = CANVAS_Y.toPx()
        fun pt(o: Offset) = Offset(ox + o.x.dp.toPx(), oy + o.y.dp.toPx())
        drawGamepad(this, pad, ink, ox, oy)
        val line = ink.copy(alpha = 0.32f)
        PadGeometry.rightColumn.forEachIndexed { i, n ->
            val from = pt(PadGeometry.anchors.getValue(n)); val to = Offset(RIGHT_X.toPx(), (ROW_Y0 + i * ROW_PITCH + 18f).dp.toPx())
            drawLine(if (n in pad.pressed) ink else line, from, to, strokeWidth = if (n in pad.pressed) 2.dp.toPx() else 1.dp.toPx())
        }
        PadGeometry.bottomRow.forEachIndexed { i, n ->
            val from = pt(PadGeometry.anchors.getValue(n)); val to = Offset(CANVAS_X.toPx() + (i * BOTTOM_PITCH + 34f).dp.toPx(), BOTTOM_Y.dp.toPx())
            drawLine(if (n in pad.pressed) ink else line, from, to, strokeWidth = if (n in pad.pressed) 2.dp.toPx() else 1.dp.toPx())
        }
    }
    PadGeometry.rightColumn.forEachIndexed { i, n ->
        val b = buttons[n] ?: return@forEachIndexed
        MapPill(n, actionText(b), n in pad.pressed, Modifier.offset(RIGHT_X, ROW_Y0.dp + (i * ROW_PITCH).dp).width(RIGHT_W).testTag("pad-label:$n")) { openMapping(RemoteButtons.padKey(controller, b.id.removePrefix("pad:"))) }
    }
    PadGeometry.bottomRow.forEachIndexed { i, n ->
        val b = buttons[n] ?: return@forEachIndexed
        MapPill(n, actionText(b), n in pad.pressed, Modifier.offset(CANVAS_X + (i * BOTTOM_PITCH).dp, BOTTOM_Y.dp).width(68.dp).testTag("pad-label:$n")) { openMapping(RemoteButtons.padKey(controller, b.id.removePrefix("pad:"))) }
    }
    StickPanel(controller, pad, cfg, edit)
}

/** The gamepad drawing: body, D-pad, face buttons, sticks (with the live dot), bumpers and triggers; a pressed part is filled solid. */
private fun drawGamepad(scope: DrawScope, pad: PadState, ink: Color, ox: Float, oy: Float) = with(scope) {
    fun px(v: Float) = v.dp.toPx()
    fun at(o: Offset) = Offset(ox + px(o.x), oy + px(o.y))
    val fill = ink.copy(alpha = 0.08f); val rim = ink.copy(alpha = 0.34f); val lit = ink
    fun part(pressed: Boolean) = if (pressed) lit else fill
    // Body: a wide rounded shape with two grips.
    drawRoundRect(fill, Offset(ox + px(20f), oy + px(40f)), Size(px(320f), px(140f)), CornerRadius(px(64f)))
    drawRoundRect(rim, Offset(ox + px(20f), oy + px(40f)), Size(px(320f), px(140f)), CornerRadius(px(64f)), style = Stroke(px(1.5f)))
    rotate(14f, at(Offset(66f, 190f))) {
        drawRoundRect(fill, Offset(ox + px(26f), oy + px(120f)), Size(px(80f), px(138f)), CornerRadius(px(40f)))
        drawRoundRect(rim, Offset(ox + px(26f), oy + px(120f)), Size(px(80f), px(138f)), CornerRadius(px(40f)), style = Stroke(px(1.5f)))
    }
    rotate(-14f, at(Offset(294f, 190f))) {
        drawRoundRect(fill, Offset(ox + px(254f), oy + px(120f)), Size(px(80f), px(138f)), CornerRadius(px(40f)))
        drawRoundRect(rim, Offset(ox + px(254f), oy + px(120f)), Size(px(80f), px(138f)), CornerRadius(px(40f)), style = Stroke(px(1.5f)))
    }
    // Triggers (analog fill) and bumpers.
    fun bar(x: Float, y: Float, w: Float, h: Float, amount: Float, pressed: Boolean) {
        drawRoundRect(fill, Offset(ox + px(x), oy + px(y)), Size(px(w), px(h)), CornerRadius(px(4f)))
        if (pressed || amount > 0f) drawRoundRect(lit, Offset(ox + px(x), oy + px(y)), Size(px(w * (if (pressed) 1f else amount)), px(h)), CornerRadius(px(4f)))
        drawRoundRect(rim, Offset(ox + px(x), oy + px(y)), Size(px(w), px(h)), CornerRadius(px(4f)), style = Stroke(px(1f)))
    }
    bar(60f, 4f, 60f, 14f, pad.leftTrigger, "LT" in pad.pressed); bar(240f, 4f, 60f, 14f, pad.rightTrigger, "RT" in pad.pressed)
    drawRoundRect(part("LB" in pad.pressed), Offset(ox + px(50f), oy + px(26f)), Size(px(76f), px(13f)), CornerRadius(px(6f)))
    drawRoundRect(rim, Offset(ox + px(50f), oy + px(26f)), Size(px(76f), px(13f)), CornerRadius(px(6f)), style = Stroke(px(1f)))
    drawRoundRect(part("RB" in pad.pressed), Offset(ox + px(234f), oy + px(26f)), Size(px(76f), px(13f)), CornerRadius(px(6f)))
    drawRoundRect(rim, Offset(ox + px(234f), oy + px(26f)), Size(px(76f), px(13f)), CornerRadius(px(6f)), style = Stroke(px(1f)))
    // D-pad: a cross, each arm lit by its direction.
    val d = PadGeometry.dpad
    fun arm(name: String, dx: Float, dy: Float, w: Float, h: Float) {
        val c = at(Offset(d.x + dx, d.y + dy))
        drawRoundRect(part(name in pad.pressed || (name == "Up" && pad.hatY < 0) || (name == "Down" && pad.hatY > 0) || (name == "Left" && pad.hatX < 0) || (name == "Right" && pad.hatX > 0)),
            Offset(c.x - px(w / 2), c.y - px(h / 2)), Size(px(w), px(h)), CornerRadius(px(5f)))
        drawRoundRect(rim, Offset(c.x - px(w / 2), c.y - px(h / 2)), Size(px(w), px(h)), CornerRadius(px(5f)), style = Stroke(px(1f)))
    }
    arm("Up", 0f, -19f, 22f, 24f); arm("Down", 0f, 19f, 22f, 24f); arm("Left", -19f, 0f, 24f, 22f); arm("Right", 19f, 0f, 24f, 22f)
    // Face buttons.
    for ((n, o) in listOf("Y" to Offset(265f, 65f), "X" to Offset(235f, 95f), "B" to Offset(295f, 95f), "A" to Offset(265f, 125f))) {
        drawCircle(part(n in pad.pressed), px(14f), at(o)); drawCircle(rim, px(14f), at(o), style = Stroke(px(1.2f)))
    }
    // Centre buttons.
    drawCircle(part("Guide" in pad.pressed), px(8f), at(PadGeometry.anchors.getValue("Guide"))); drawCircle(rim, px(8f), at(PadGeometry.anchors.getValue("Guide")), style = Stroke(px(1f)))
    for (n in listOf("Select", "Start")) {
        val c = at(PadGeometry.anchors.getValue(n)); drawRoundRect(part(n in pad.pressed), Offset(c.x - px(9f), c.y - px(5f)), Size(px(18f), px(10f)), CornerRadius(px(5f)))
        drawRoundRect(rim, Offset(c.x - px(9f), c.y - px(5f)), Size(px(18f), px(10f)), CornerRadius(px(5f)), style = Stroke(px(1f)))
    }
    // Sticks: the ring, filled when pressed; the dot is where the stick really is.
    for ((n, o, x, y) in listOf(Quad("L3", PadGeometry.leftStick, pad.leftX, pad.leftY), Quad("R3", PadGeometry.rightStick, pad.rightX, pad.rightY))) {
        val c = at(o)
        drawCircle(part(n in pad.pressed), px(PadGeometry.STICK_R), c); drawCircle(rim, px(PadGeometry.STICK_R), c, style = Stroke(px(1.5f)))
        drawCircle(if (n in pad.pressed) fill else lit, px(6f), Offset(c.x + x * px(PadGeometry.STICK_R - 7f), c.y + y * px(PadGeometry.STICK_R - 7f)))
    }
}

private data class Quad(val name: String, val at: Offset, val x: Float, val y: Float)

/** The sticks' dead zone and sensitivity (the left panel): which stick, which tab, the live circle, the X and Y sliders and Reset. */
@Composable
private fun StickPanel(controller: String, pad: PadState, cfg: LauncherConfig, edit: ((LauncherConfig) -> LauncherConfig) -> Unit) {
    val palette = LocalPalette.current
    var right by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    val tuning = cfg.sticksOf(controller).let { if (right) it.right else it.left }
    val ink = palette.primary
    Column(
        Modifier.offset(0.dp, 44.dp).width(235.dp).height(398.dp).background(ink.copy(alpha = 0.06f), RoundedCornerShape(18.dp)).padding(12.dp).testTag("stick-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DevPill(if (right) "Right Stick" else "Left Stick", Modifier.testTag("stick-which"), onClick = { right = !right }) { fg -> Text(if (right) "Right Stick" else "Left Stick", style = Type.body, color = fg, maxLines = 1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Deadzone", "Sensitivity").forEachIndexed { i, t -> DevPill(t, Modifier.testTag("stick-tab:$t"), selected = tab == i, onClick = { tab = i }) { fg -> Text(t, style = Type.secondary, color = fg, maxLines = 1) } }
        }
        // The live circle: the dead zone as its inner ring, the dot following the stick (scaled by sensitivity on the second tab).
        val sx = if (right) pad.rightX else pad.leftX; val sy = if (right) pad.rightY else pad.leftY
        Canvas(Modifier.align(Alignment.CenterHorizontally).size(132.dp).testTag("stick-circle")) {
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

// ── remote ───────────────────────────────────────────────────────────────────

@Composable
private fun RemoteBody(name: String, lit: Set<String>, cfg: LauncherConfig, edit: ((LauncherConfig) -> LauncherConfig) -> Unit, openMapping: (String) -> Unit) {
    val palette = LocalPalette.current
    val ink = palette.primary
    val pm = LocalContext.current.packageManager
    val mappable = RemoteButtons.all
    val ox = 330.dp; val oy = 62.dp
    Canvas(Modifier.fillMaxSize()) {
        fun px(v: Float) = v.dp.toPx()
        val x0 = ox.toPx(); val y0 = oy.toPx()
        fun at(o: Offset) = Offset(x0 + px(o.x), y0 + px(o.y))
        val fill = ink.copy(alpha = 0.08f); val rim = ink.copy(alpha = 0.34f)
        fun part(id: String) = if (id in lit) ink else fill
        drawRoundRect(fill, Offset(x0, y0), Size(px(RemoteGeometry.W), px(RemoteGeometry.H)), CornerRadius(px(36f)))
        drawRoundRect(rim, Offset(x0, y0), Size(px(RemoteGeometry.W), px(RemoteGeometry.H)), CornerRadius(px(36f)), style = Stroke(px(1.5f)))
        // The ring and its centre.
        drawCircle(fill, px(46f), at(Offset(70f, 56f))); drawCircle(rim, px(46f), at(Offset(70f, 56f)), style = Stroke(px(1.2f)))
        for (id in listOf("up", "down", "left", "right")) { val c = at(RemoteGeometry.anchors.getValue(id)); if (id in lit) drawCircle(ink.copy(alpha = 0.9f), px(10f), c) }
        drawCircle(part("select"), px(18f), at(Offset(70f, 56f))); drawCircle(rim, px(18f), at(Offset(70f, 56f)), style = Stroke(px(1f)))
        for (id in listOf("back", "home", "menu", "rewind", "playpause", "ff", "app1", "app2", "app3", "app4", "recents", "settings")) {
            val c = at(RemoteGeometry.anchors.getValue(id)); drawCircle(part(id), px(11f), c); drawCircle(rim, px(11f), c, style = Stroke(px(1f)))
        }
        mappable.forEachIndexed { i, b ->
            val anchor = RemoteGeometry.anchors[b.id] ?: return@forEachIndexed
            val from = at(anchor); val to = Offset(RIGHT_X.toPx(), (ROW_Y0 + i * ROW_PITCH + 18f).dp.toPx())
            drawLine(if (b.id in lit) ink else ink.copy(alpha = 0.32f), from, to, strokeWidth = if (b.id in lit) 2.dp.toPx() else 1.dp.toPx())
        }
    }
    mappable.forEachIndexed { i, b ->
        val a = RemoteButtons.action(b, cfg.remoteButtons)
        MapPill(b.label, dev.glasslauncher.settings.actionLabel(a, pm), b.id in lit, Modifier.offset(RIGHT_X, ROW_Y0.dp + (i * ROW_PITCH).dp).width(RIGHT_W).testTag("remote-label:${b.id}")) { openMapping(b.id) }
    }
    // Left panel: the remote's state and a reset of its mapping.
    val rowContext = LocalContext.current
    val rows = remember(name) { RemoteList.read(rowContext) }
    Column(
        Modifier.offset(0.dp, 44.dp).width(235.dp).height(200.dp).background(ink.copy(alpha = 0.06f), RoundedCornerShape(18.dp)).padding(12.dp).testTag("remote-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name.ifEmpty { "Remote" }, style = Type.body, color = ink, maxLines = 2)
        Text(rows.firstOrNull { it.name == name }?.line ?: "Connected", style = Type.secondary, color = palette.secondary, maxLines = 2)
        DevPill("Reset to Default", Modifier.fillMaxWidth().testTag("remote-reset"), onClick = { edit { c -> c.copy(remoteButtons = c.remoteButtons.filterKeys { k -> mappable.none { it.id == k } }) } }) { fg -> Text("Reset to Default", style = Type.body, color = fg, maxLines = 1) }
    }
}

// ── shared bits ──────────────────────────────────────────────────────────────

/** A focusable capsule: translucent at rest, white (dark text) when focused; [selected] lights it lightly. */
@Composable
private fun DevPill(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit, content: @Composable (Color) -> Unit) {
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
            .focusable().padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) { content(if (focused) Color.Black else ink) }
}

/** A button's label: its name over what it does now; lit while the button is held. Select opens its mapping. */
@Composable
private fun MapPill(name: String, mapping: String, lit: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ink = LocalPalette.current.primary
    var focused by remember { mutableStateOf(false) }
    val pressed = remember { booleanArrayOf(false) }
    Column(
        modifier.height(40.dp)
            .background(if (focused) Color.White else ink.copy(alpha = if (lit) 0.34f else 0.10f), RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (!isSelectKey(e.key.nativeKeyCode)) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) pressed[0] = true
                if (e.type == KeyEventType.KeyUp && pressed[0]) { pressed[0] = false; onClick() }
                true
            }
            .semantics { role = Role.Button; contentDescription = "$name, $mapping" }
            .focusable().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val fg = if (focused) Color.Black else ink
        Text(name, style = Type.secondary, color = fg, maxLines = 1)
        Text(mapping, style = Type.secondary, color = fg.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
