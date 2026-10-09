package dev.glasslauncher.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import java.io.File

/** D-pad and remote buttons, as Compose key events. */
enum class Button(val key: Key) {
    Up(Key.DirectionUp), Down(Key.DirectionDown), Left(Key.DirectionLeft), Right(Key.DirectionRight),
    Select(Key.DirectionCenter), Back(Key.Back), Menu(Key.Menu),
}

/** Presses each button in turn and lets the UI settle after each. */
@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.press(vararg buttons: Button) {
    for (b in buttons) {
        onRoot().performKeyInput { pressKey(b.key) }
        waitForIdle()
    }
}

/**
 * A readable name for whatever has focus: its own test tag, else the nearest tagged ancestor's tag
 * plus the node's text, else its text or content description. Null when nothing is focused.
 */
fun ComposeTestRule.focused(): String? {
    val node = onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull() ?: return null
    return describe(node)
}

fun describe(node: SemanticsNode): String {
    val own = node.config.getOrNull(SemanticsProperties.TestTag)
    if (own != null) return own
    val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
        ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        ?: node.children.firstNotNullOfOrNull { c -> c.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }
    var p = node.parent
    while (p != null) {
        p.config.getOrNull(SemanticsProperties.TestTag)?.let { return if (text != null) "$it/$text" else it }
        p = p.parent
    }
    return text ?: "node#${node.id}"
}

/**
 * Frame-exact motion review: pauses the clock, presses [button], then captures [frames] frames
 * [stepMs] apart and tiles them into one image (left to right, top to bottom) at [scale].
 * Written to build/strips/<name>.png. Opening one image shows the whole transition.
 */
@OptIn(ExperimentalTestApi::class)
/** Presses [button] and captures [frames] frames [stepMs] apart on a paused clock (scaled by [scale]). */
fun ComposeTestRule.frames(button: Button, frames: Int, stepMs: Long, scale: Float = 0.25f): List<Bitmap> =
    frames({ onRoot().performKeyInput { pressKey(button.key) } }, frames, stepMs, scale)

/** Frames after [start] (e.g. the activity's Back, which overlays take through their BackHandler). */
fun ComposeTestRule.frames(start: () -> Unit, frames: Int, stepMs: Long, scale: Float = 0.25f): List<Bitmap> {
    mainClock.autoAdvance = false
    val shots = ArrayList<Bitmap>(frames)
    try {
        start()
        repeat(frames) {
            if (it == 0) mainClock.advanceTimeByFrame() else mainClock.advanceTimeBy(stepMs)
            val full = onRoot().captureToImage().asAndroidBitmap()
            shots += Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
        }
    } finally {
        mainClock.autoAdvance = true
    }
    return shots
}

fun ComposeTestRule.strip(
    name: String,
    button: Button,
    frames: Int = 12,
    stepMs: Long = 32,
    columns: Int = 4,
    scale: Float = 0.25f,
): File {
    return sheet(name, frames(button, frames, stepMs, scale), stepMs, columns)
}

/** Tiles [shots] (taken [stepMs] apart) into build/strips/<name>.png, labelled with their times. */
fun sheet(name: String, shots: List<Bitmap>, stepMs: Long, columns: Int = 4): File {
    val frames = shots.size
    val w = shots[0].width; val h = shots[0].height; val gap = 4
    val rows = (frames + columns - 1) / columns
    val sheet = Bitmap.createBitmap(columns * (w + gap) - gap, rows * (h + gap) - gap, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet)
    canvas.drawColor(android.graphics.Color.BLACK)
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.YELLOW; textSize = 18f }
    shots.forEachIndexed { i, b ->
        val x = (i % columns) * (w + gap).toFloat(); val y = (i / columns) * (h + gap).toFloat()
        canvas.drawBitmap(b, x, y, null)
        canvas.drawText("${i * stepMs} ms", x + 6, y + 22, label)
    }
    val out = File("build/strips/$name.png").apply { parentFile?.mkdirs() }
    out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return out
}

/** Lets animations, the backdrop swap and off-thread image decodes finish before a capture. */
fun ComposeTestRule.settle() {
    // Image decodes and backdrop blurs run on real background threads, so give them real time too.
    repeat(10) {
        mainClock.advanceTimeBy(300)
        Thread.sleep(80)
        waitForIdle()
    }
}

/**
 * Focuses the node tagged [tag]: directly when it's composed, otherwise by walking Down/Right
 * through the list until it appears (lazy rows aren't composed until scrolled near).
 */
fun ComposeTestRule.focusTag(tag: String) {
    fun tryFocus(): Boolean {
        val node = onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?: return false
        onNode(androidx.compose.ui.test.SemanticsMatcher("id ${node.id}") { it.id == node.id }, useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        waitForIdle()
        return focused() == tag
    }
    repeat(8) {
        if (tryFocus()) return
        press(Button.Down)
    }
    error("couldn't focus $tag (focus is on ${focused()})")
}
