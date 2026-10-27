package dev.glasslauncher.p239spike

import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Region
import android.graphics.Shader
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * P239 spike: Home's dock and app grid (6 dock tiles, 4 grid rows of 6, a label under every grid tile), the tray glass,
 * focus scale + shadow, the scroll to the grid with its scroll blur, and the backdrop cross-fade.
 * `--es mode compose`: everything in Compose, the way Glass draws it today (HARDWARE bitmaps, a BitmapShader glass,
 * a baked shadow bitmap, graphicsLayer clip + scale, the blur ladder as one rung drawn per frame).
 * `--es mode native`: the Rust renderer in a SurfaceView, Compose drawing the labels in one translucent window above.
 * `--es mode nativelabels`: the labels drawn natively too (a text atlas made by Android's Canvas); Compose draws nothing.
 * `--ez markers true`: skew markers under every grid tile: magenta drawn with the tile, cyan drawn with its label.
 * `--ez etc2 true`: the backdrops' mip chains uploaded as ETC2. Keys: arrows move focus, Center cross-fades.
 */
class SpikeActivity : ComponentActivity() {
    private val focus = mutableIntStateOf(0)
    private val swaps = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(null)
        val mode = intent.getStringExtra("mode") ?: "compose"
        val markers = intent.getBooleanExtra("markers", false)
        val art = Art.make()
        if (mode == "compose") {
            setContent { Home(art, native = false, labels = true, markers = markers) }
            return
        }
        if (mode == "posecheck") poseCheck()
        val nativeLabels = mode == "nativelabels"
        window.setFormat(PixelFormat.TRANSLUCENT)
        val root = FrameLayout(this)
        // With Compose labels, no transparent-region hole: the label window stays one composed layer over the native
        // one (P266). Without, the default hole leaves the window nothing to show, so SurfaceFlinger drops it.
        val sv = if (!nativeLabels) object : SurfaceView(this) { override fun gatherTransparentRegion(region: Region?): Boolean = true } else SurfaceView(this)
        sv.holder.setFormat(PixelFormat.OPAQUE)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                Native.start(h.surface)
                Native.partial(intent.getBooleanExtra("partial", true))
                Native.flags((if (nativeLabels) 1 else 0) or (if (markers) 2 else 0))
                val etc2 = intent.getBooleanExtra("etc2", false)
                Thread {
                    art.backdrops.forEachIndexed { slot, mips -> mips.forEachIndexed { level, b -> Native.upload(slot, level, b, etc2) } }
                    art.tiles.forEachIndexed { i, b -> Native.upload(2 + i, 0, b, false) }
                    Native.upload(8, 0, art.labelAtlas, false)
                    Native.frame(0f, 0f, 0f, 0, 0, 1.224f, -1, 1f)
                }.start()
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) = Native.stop()
        })
        root.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(ComposeView(this).apply {
            setContent { Home(art, native = true, labels = !nativeLabels, markers = markers) }
            // Native labels: the Compose view still runs the animations that drive the native frames, but draws nothing.
            if (nativeLabels) visibility = android.view.View.INVISIBLE
        })
        setContentView(root)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val f = focus.intValue
        val (row, col) = if (f < 6) -1 to f else (f - 6) / 6 to (f - 6) % 6
        fun at(r: Int, c: Int) = if (r < 0) c else 6 + r * 6 + c
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> focus.intValue = at(row, (col + 1).coerceAtMost(5))
            KeyEvent.KEYCODE_DPAD_LEFT -> focus.intValue = at(row, (col - 1).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_DOWN -> focus.intValue = at((row + 1).coerceAtMost(3), col)
            KeyEvent.KEYCODE_DPAD_UP -> focus.intValue = at((row - 1).coerceAtLeast(-1), col)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { android.util.Log.i("P239", "swap,${System.nanoTime()}"); swaps.intValue++ }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    @Composable
    private fun Home(art: Art, native: Boolean, labels: Boolean, markers: Boolean) {
        val scales: List<State<Float>> = (0 until SLOTS).map { i ->
            val focused = focus.intValue == i
            animateFloatAsState(if (focused) 1.224f else 1f, if (focused) spring(0.65f, 850f) else spring(1f, 500f), label = "s$i")
        }
        // The scroll to the focused grid row (Metrics.gridPivot: its top settles at 362 px), and the blur that follows it.
        val scroll = remember { Animatable(0f) }
        LaunchedEffect(focus.intValue) {
            val f = focus.intValue
            val target = if (f < 6) 0f else GRID_Y + ((f - 6) / 6) * ROW - 362f
            if (target != scroll.targetValue) scroll.animateTo(target, tween(600, easing = FastOutSlowInEasing))
        }
        val fade = remember { Animatable(0f) }
        val current = remember { mutableIntStateOf(0) }
        LaunchedEffect(swaps.intValue) {
            if (swaps.intValue == 0) return@LaunchedEffect
            fade.snapTo(0f)
            fade.animateTo(1f, tween(550))
            current.intValue = 1 - current.intValue
            fade.snapTo(0f)
        }
        val noLod = intent.getBooleanExtra("nolod", false)
        // Control: the native layer gets scroll 0 (but still one new frame per scroll step) while Kotlin animates as usual.
        val freeze = intent.getBooleanExtra("freeze", false)
        fun lod() = if (noLod) 0f else 3f * (scroll.value / (GRID_Y - 362f)).coerceIn(0f, 1f)
        if (native) {
            LaunchedEffect(Unit) {
                snapshotFlow {
                    val moving = scales.indices.filter { kotlin.math.abs(scales[it].value - 1f) > 0.0005f }.sortedByDescending { kotlin.math.abs(scales[it].value - 1f) }
                    floatArrayOf(scroll.value, lod(), fade.value, current.intValue.toFloat(),
                        moving.getOrElse(0) { -1 }.toFloat(), moving.getOrNull(0)?.let { scales[it].value } ?: 1f,
                        moving.getOrElse(1) { -1 }.toFloat(), moving.getOrNull(1)?.let { scales[it].value } ?: 1f).toList()
                }.collect { v -> Native.frame(if (freeze) 0f else v[0], v[1], v[2], v[3].toInt(), v[4].toInt(), v[5], v[6].toInt(), v[7] + if (freeze) v[0] * 1e-7f else 0f) }
            }
        }
        val px = LocalDensity.current
        Box(Modifier.fillMaxSize()) {
            if (!native) {
                // Glass's scroll blur: the ladder, one opaque rung per frame (here the mip levels, drawn full screen).
                val from = current.intValue
                val rung = lod().roundToInt().coerceIn(0, 4)
                Image(art.ladder[from][rung], null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                if (fade.value > 0f) Image(art.ladder[1 - from][rung], null, Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }, contentScale = ContentScale.FillBounds)
                Glass(art, from, fade.value) { scroll.value }
                for (i in 0 until SLOTS) Tile(art, i, scales[i]) { scroll.value }
            }
            if (!labels) return@Box
            for (slot in 6 until SLOTS) {
                val (x, y) = labelAt(slot)
                BasicText(
                    "App ${slot - 5}", Modifier.offset { IntOffset(x.roundToInt(), (y - scroll.value).roundToInt()) },
                    style = TextStyle(color = Color.White, fontSize = with(px) { 26f.toSp() }),
                )
                if (markers) Box(Modifier.offset { IntOffset(x.roundToInt() + 50, (y - 8 - scroll.value).roundToInt()) }.size(with(px) { 40f.toDp() }, with(px) { 6f.toDp() }).background(Color.Cyan))
            }
        }
    }

    /** Glass's dock tray today: the baked clear texture through a shader, a tint, and a rim stroke (both scenes while fading). */
    @Composable
    private fun Glass(art: Art, from: Int, fade: Float, scroll: () -> Float) {
        Canvas(Modifier.fillMaxSize()) {
            translate(top = -scroll()) {
                val tl = Offset(TRAY[0], TRAY[1]); val sz = Size(TRAY[2], TRAY[3]); val r = CornerRadius(TRAY_R)
                val scale = android.graphics.Matrix().apply { setScale(size.width / art.clearW, size.height / art.clearH) }
                for ((k, alpha) in listOf(from to 1f, (1 - from) to fade)) {
                    if (alpha <= 0f) continue
                    val shader = ImageShader(art.clear[k]).also { it.setLocalMatrix(scale) }
                    drawRoundRect(ShaderBrush(shader), tl, sz, r, alpha = alpha)
                }
                drawRoundRect(Color(0x26FFFFFF), tl, sz, r)
                drawRoundRect(Color(0x59FFFFFF), tl, sz, r, style = Stroke(2.5f))
            }
        }
    }

    /** A tile as FocusTile draws it: scaled and clipped by a graphicsLayer, over a baked shadow bitmap. */
    @Composable
    private fun Tile(art: Art, slot: Int, scale: State<Float>, scroll: () -> Float) {
        val (x, y) = tileAt(slot)
        val px = LocalDensity.current
        val s = scale.value
        val focus = ((s - 1f) / 0.224f).coerceIn(0f, 1f)
        if (focus > 0.01f) {
            Image(
                art.shadow, null,
                Modifier.offset { IntOffset((x - SHADOW).roundToInt(), (y - SHADOW + 14 - scroll()).roundToInt()) }
                    .size(with(px) { (TILE_W + 2 * SHADOW).toDp() }, with(px) { (TILE_H + 2 * SHADOW).toDp() })
                    .graphicsLayer { scaleX = s; scaleY = s; alpha = focus },
                contentScale = ContentScale.FillBounds,
            )
        }
        Image(
            art.tileImages[slot % 6], null,
            Modifier.offset { IntOffset(x.roundToInt(), (y - scroll()).roundToInt()) }.size(with(px) { TILE_W.toDp() }, with(px) { TILE_H.toDp() })
                .graphicsLayer { scaleX = s; scaleY = s; shape = RoundedCornerShape(TILE_R); clip = true },
            contentScale = ContentScale.FillBounds,
        )
    }

    companion object {
        const val TILE_W = 250f; const val TILE_H = 150f; const val TILE_R = 30f; const val GUTTER = 48f
        const val SHADOW = 60f; const val TRAY_R = 60f; const val GRID_Y = 1070f; const val ROW = 260f; const val SLOTS = 30
        val TRAY = floatArrayOf(44f, 768f, 1832f, 230f)
        /** Top-left of a slot's tile at scroll 0 (same layout as the Rust renderer's tile_rect). */
        fun tileAt(slot: Int): Pair<Float, Float> {
            val (col, y) = if (slot < 6) slot to 776f else (slot - 6) % 6 to GRID_Y + ((slot - 6) / 6) * ROW
            return (90f + col * (TILE_W + GUTTER)) to y
        }
        fun labelAt(slot: Int): Pair<Float, Float> = tileAt(slot).let { (x, y) -> x to y + TILE_H + 12f }
    }
}

/** The scene's pictures, generated (no image assets). */
class Art(
    val backdrops: List<List<Bitmap>>, val ladder: List<List<ImageBitmap>>, val clear: List<ImageBitmap>, val clearW: Float, val clearH: Float,
    val tiles: List<Bitmap>, val tileImages: List<ImageBitmap>, val shadow: ImageBitmap, val labelAtlas: Bitmap,
) {
    companion object {
        fun make(): Art {
            val scenes = listOf(scene(0xFF0B1026.toInt(), intArrayOf(0xCC3A2E8F.toInt(), 0xB30E7C86.toInt(), 0xA67B2F7F.toInt()), 1), scene(0xFF140A0E.toInt(), intArrayOf(0xCCB4232C.toInt(), 0xB3E07A2F.toInt(), 0x995B1A7A.toInt()), 2))
            // Pre-blurred mip chain: 1920x1080 sharp, then each half size blurred more.
            val mips = scenes.map { s -> generateSequence(s) { prev -> if (prev.width <= 120) null else soften(prev) }.take(5).toList() }
            val ladder = mips.map { m -> m.map { it.copy(Bitmap.Config.HARDWARE, false).asImageBitmap() } }
            val clear = mips.map { it[2].copy(Bitmap.Config.HARDWARE, false).asImageBitmap() }
            val tiles = (0 until 6).map { tile(it) }
            return Art(mips, ladder, clear, mips[0][2].width.toFloat(), mips[0][2].height.toFloat(), tiles,
                tiles.map { it.copy(Bitmap.Config.HARDWARE, false).asImageBitmap() }, shadow().copy(Bitmap.Config.HARDWARE, false).asImageBitmap(), atlas())
        }

        private fun scene(base: Int, blobs: IntArray, seed: Int): Bitmap {
            val b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            val c = ACanvas(b)
            c.drawColor(base)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            blobs.forEachIndexed { i, col ->
                val cx = 1920f * ((i * 0.37f + seed * 0.21f) % 1f); val cy = 1080f * ((i * 0.53f + seed * 0.3f) % 1f); val r = 1100f
                p.shader = RadialGradient(cx, cy, r, intArrayOf(col, col and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r, p)
            }
            val line = Paint().apply { strokeWidth = 1f }
            val rnd = java.util.Random(seed.toLong())
            repeat(1500) { line.color = AColor.argb(30 + rnd.nextInt(50), 255, 255, 255); val x = rnd.nextFloat() * 1920; val y = rnd.nextFloat() * 1080; c.drawLine(x, y, x + 40 * rnd.nextFloat(), y + 6, line) }
            return b
        }

        private fun soften(src: Bitmap): Bitmap {
            var b = Bitmap.createScaledBitmap(src, src.width / 2, src.height / 2, true)
            repeat(3) { val s = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true); b = Bitmap.createScaledBitmap(s, src.width / 2, src.height / 2, true) }
            return b
        }

        private fun tile(i: Int): Bitmap {
            val b = Bitmap.createBitmap(250, 150, Bitmap.Config.ARGB_8888)
            val c = ACanvas(b)
            val hue = i * 60f
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 250f, 150f, AColor.HSVToColor(floatArrayOf(hue, 0.7f, 0.9f)), AColor.HSVToColor(floatArrayOf(hue + 40, 0.8f, 0.5f)), Shader.TileMode.CLAMP) })
            c.drawText("${i + 1}", 100f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE; textSize = 70f })
            return b
        }

        private fun shadow(): Bitmap {
            val b = Bitmap.createBitmap(370, 270, Bitmap.Config.ARGB_8888)
            ACanvas(b).drawRoundRect(60f, 60f, 310f, 210f, 30f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AColor.argb(115, 0, 0, 0); maskFilter = android.graphics.BlurMaskFilter(28f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            })
            return b
        }

        /** Every grid label pre-rendered by Android's own text stack into one atlas (200x40 cells, 10 per row). */
        private fun atlas(): Bitmap {
            val b = Bitmap.createBitmap(2000, 120, Bitmap.Config.ARGB_8888)
            val c = ACanvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE; textSize = 26f }
            for (slot in 6 until 30) c.drawText("App ${slot - 5}", (slot % 10) * 200f, (slot / 10) * 40f + 30f, p)
            return b
        }
    }

}

/**
     * Diffs the ported Rust geometry against the Kotlin original on the real ABI (P239): f32 width, JNI naming and
     * struct order are all things a host-side test cannot catch.
     */
    private fun poseCheck() {
        var maxDelta = 0f
        var n = 0
        var visibleMismatch = 0
        var off = -6f
        while (off <= 6f) {
            val k = dev.glasslauncher.featured.CoverFlowGeometry.pose(off)
            val want = floatArrayOf(k.x, k.rotationY, k.scale, k.z, k.alpha)
            for (f in 0..4) {
                val d = kotlin.math.abs(Native.poseField(off, f) - want[f])
                if (d > maxDelta) maxDelta = d
                n++
            }
            val kv = if (dev.glasslauncher.featured.CoverFlowGeometry.visible(off)) 1f else 0f
            if (kv != Native.poseField(off, 5)) visibleMismatch++
            off += 0.05f
        }
        android.util.Log.i("P239", "posecheck,n=$n,maxdelta=$maxDelta,visiblemismatch=$visibleMismatch")
    }
