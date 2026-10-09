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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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

@OptIn(ExperimentalTestApi::class)
/** Presses [button] and captures [frames] frames [stepMs] apart on a paused clock (scaled by [scale]). */
fun ComposeTestRule.frames(button: Button, frames: Int, stepMs: Long, scale: Float = 0.25f, onFrame: (Int) -> Unit = {}): List<Bitmap> =
    frames({ onRoot().performKeyInput { pressKey(button.key) } }, frames, stepMs, scale, onFrame)

/** Frames after [start] (e.g. the activity's Back, which overlays take through their BackHandler). */
fun ComposeTestRule.frames(start: () -> Unit, frames: Int, stepMs: Long, scale: Float = 0.25f, onFrame: (Int) -> Unit = {}): List<Bitmap> {
    mainClock.autoAdvance = false
    val shots = ArrayList<Bitmap>(frames)
    try {
        start()
        repeat(frames) {
            onFrame(it)
            if (it == 0) mainClock.advanceTimeByFrame() else mainClock.advanceTimeBy(stepMs)
            val full = onRoot().captureToImage().asAndroidBitmap()
            shots += Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
        }
    } finally {
        mainClock.autoAdvance = true
    }
    return shots
}

/**
 * Frame-exact motion review: pauses the clock, presses [button], then captures [frames] frames [stepMs] apart and
 * tiles them into one image (left to right, top to bottom) at [scale], written to build/strips/<name>.png.
 */
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

/**
 * Lets animations, the backdrop swap and off-thread image decodes finish before a capture.
 *
 * Image decodes and backdrop bakes run on real background threads, so a fixed sleep is a race that a cold or busy
 * JVM loses (a first run after a compile, a machine that is paging): the picture then changes mid-measurement
 * and a continuity or ghost-frame check fails on one test, then passes on the rerun. So after the usual minimum
 * this waits until the bake thread has drained and the picture has stopped changing ([untilStill]).
 */
fun ComposeTestRule.settle() {
    repeat(10) {
        mainClock.advanceTimeBy(300)
        Thread.sleep(20)
        waitForIdle()
    }
    untilStill()
}

/** How many captures in a row must match the one before, and how far apart in real time, for the picture to count as still. */
private const val STILL_ROUNDS = 4
private const val STILL_GAP_MS = 60L
private const val STILL_MAX_ROUNDS = 30

/** Waits for the bake thread to finish whatever it has queued (it is one FIFO thread: an empty task runs after them). */
fun awaitBakes() = runBlocking { withContext(dev.glasslauncher.glass.WallpaperLoader.BakeDispatcher) { } }

/**
 * Drains the bake thread, then lets time pass until the picture is the same in [STILL_ROUNDS] captures after the
 * first (or [STILL_MAX_ROUNDS] rounds are up: something that never stops, like a looping animation, is not waited
 * on forever). Capped by rounds, not by wall time, so the virtual time advanced is the same on every machine.
 */
fun ComposeTestRule.untilStill() {
    var same = 0
    var last: Int? = null
    var rounds = 0
    while (same < STILL_ROUNDS && rounds++ < STILL_MAX_ROUNDS) {
        awaitBakes()
        mainClock.advanceTimeBy(100)
        Thread.sleep(STILL_GAP_MS)
        waitForIdle()
        val bitmap = onRoot().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        val hash = pixels.contentHashCode()
        same = if (hash == last) same + 1 else 0
        last = hash
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
