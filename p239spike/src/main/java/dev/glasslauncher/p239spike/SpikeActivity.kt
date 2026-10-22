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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * P239 spike: the dock at rest on Home (backdrop, tray glass, 6 tiles, focus shadow, label, clock).
 * `--es mode compose`: everything in Compose, the way Glass draws it today (HARDWARE bitmaps, a BitmapShader glass,
 * a baked shadow bitmap, graphicsLayer clip + scale). `--es mode native`: backdrop, glass, tiles and shadow drawn by
 * the Rust renderer in a SurfaceView, Compose drawing only the label and clock in one translucent window above it.
 * Keys: Left/Right move focus, Center cross-fades the backdrop (550 ms, BackdropState.swap's length).
 */
class SpikeActivity : ComponentActivity() {
    private val focus = mutableIntStateOf(0)
    private val swaps = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(null)
        val art = Art.make(resources.displayMetrics.widthPixels.coerceAtLeast(1920))
        val lean = intent.getStringExtra("mode") == "lean"
        if (intent.getStringExtra("mode") == "native" || lean) {
            window.setFormat(PixelFormat.TRANSLUCENT)
            val root = FrameLayout(this)
            // labels=false: no Compose window drawing at all (2 HWC layers with CC's parked window, as cc-cold gives P239).
            val labels = intent.getBooleanExtra("labels", true)
            // With labels, no transparent-region hole: the label window stays one composed layer over the native one (P266).
            // Without, the default hole leaves the window nothing to show, so SurfaceFlinger drops it.
            val sv = if (labels) object : SurfaceView(this) { override fun gatherTransparentRegion(region: Region?): Boolean = true } else SurfaceView(this)
            sv.holder.setFormat(PixelFormat.OPAQUE)
            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    if (lean) {
                        Lean.gl.post {
                            if (!Lean.init(h.surface)) android.util.Log.i("P239", "lean init failed")
                            art.backdrops.forEachIndexed { slot, mips -> mips.forEachIndexed { level, b -> Lean.upload(slot, level, b) } }
                            art.tiles.forEachIndexed { i, b -> Lean.upload(2 + i, 0, b) }
                            Lean.frame(1.224f, 1f, 1f, 1f, 1f, 1f, 0f, 0f, 0)
                        }
                        return
                    }
                    Native.start(h.surface)
                    Native.partial(intent.getBooleanExtra("partial", true))
                    art.backdrops.forEachIndexed { slot, mips -> mips.forEachIndexed { level, b -> Native.upload(slot, level, b) } }
                    art.tiles.forEachIndexed { i, b -> Native.upload(2 + i, 0, b) }
                    Native.frame(1.224f, 1f, 1f, 1f, 1f, 1f, 0f, 0f, 0)
                }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {
                    if (!lean) return Native.stop()
                    val done = java.util.concurrent.CountDownLatch(1)
                    Lean.gl.post { Lean.release(); done.countDown() }
                    done.await()
                }
            })
            root.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Without labels the Compose view still runs the animations that drive the native frames, but draws nothing.
            root.addView(ComposeView(this).apply {
                setContent { Dock(art, native = true, labels = labels, lean = lean) }
                if (!labels) visibility = android.view.View.INVISIBLE
            })
            setContentView(root)
        } else {
            setContent { Dock(art, native = false) }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> focus.intValue = (focus.intValue + 1).coerceAtMost(5)
            KeyEvent.KEYCODE_DPAD_LEFT -> focus.intValue = (focus.intValue - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { android.util.Log.i("P239", "swap,${System.nanoTime()}"); swaps.intValue++ }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    @Composable
    private fun Dock(art: Art, native: Boolean, labels: Boolean = true, lean: Boolean = false) {
        val scales = (0 until 6).map { i ->
            val focused = focus.intValue == i
            animateFloatAsState(if (focused) 1.224f else 1f, if (focused) spring(0.65f, 850f) else spring(1f, 500f), label = "s$i")
        }
        // The cross-fade: 0..1 from backdrop [from] to the other; at the end the other becomes current.
        val fade = remember { Animatable(0f) }
        val current = remember { mutableIntStateOf(0) }
        LaunchedEffect(swaps.intValue) {
            if (swaps.intValue == 0) return@LaunchedEffect
            fade.snapTo(0f)
            fade.animateTo(1f, tween(550))
            current.intValue = 1 - current.intValue
            fade.snapTo(0f)
        }
        if (native) {
            LaunchedEffect(Unit) {
                snapshotFlow { scales.map { it.value } + fade.value + current.intValue.toFloat() }.collect { v ->
                    if (lean) Lean.post(v.toFloatArray()) else Native.frame(v[0], v[1], v[2], v[3], v[4], v[5], v[6], 0f, v[7].toInt())
                }
            }
        }
        val px = LocalDensity.current
        Box(Modifier.fillMaxSize()) {
            if (!native) {
                val from = current.intValue
                Image(art.sharp[from], null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                if (fade.value > 0f) Image(art.sharp[1 - from], null, Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }, contentScale = ContentScale.FillBounds)
                Glass(art, from, fade.value)
                for (i in 0 until 6) Tile(art, i, scales[i].value)
            }
            // Label under the focused tile, and the clock: in both modes, in Compose.
            if (!labels) return@Box
            val i = focus.intValue
            val x = TILE_X0 + i * (TILE_W + GUTTER)
            BasicText(
                "App ${i + 1}", Modifier.offset { IntOffset(x.roundToInt() + 70, (TILE_Y + TILE_H * 1.224f / 2 + TILE_H / 2 + 10).roundToInt()) },
                style = TextStyle(color = Color.White, fontSize = with(px) { 26f.toSp() }),
            )
            BasicText("9:41", Modifier.offset { IntOffset(1740, 40) }, style = TextStyle(color = Color.White, fontSize = with(px) { 34f.toSp() }))
        }
    }

    /** Glass's dock tray today: the baked clear texture through a shader, a tint, and a rim stroke (both scenes while fading). */
    @Composable
    private fun Glass(art: Art, from: Int, fade: Float) {
        Canvas(Modifier.fillMaxSize()) {
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

    /** A tile as FocusTile draws it: scaled and clipped by a graphicsLayer, over a baked shadow bitmap. */
    @Composable
    private fun Tile(art: Art, i: Int, s: Float) {
        val x = TILE_X0 + i * (TILE_W + GUTTER)
        val focus = ((s - 1f) / 0.224f).coerceIn(0f, 1f)
        val px = LocalDensity.current
        if (focus > 0.01f) {
            Image(
                art.shadow, null,
                Modifier.offset { IntOffset((x - SHADOW).roundToInt(), (TILE_Y - SHADOW + 14).roundToInt()) }
                    .size(with(px) { (TILE_W + 2 * SHADOW).toDp() }, with(px) { (TILE_H + 2 * SHADOW).toDp() })
                    .graphicsLayer { scaleX = s; scaleY = s; alpha = focus },
                contentScale = ContentScale.FillBounds,
            )
        }
        Image(
            art.tiles[i].asImageBitmap(), null,
            Modifier.offset { IntOffset(x.roundToInt(), TILE_Y.roundToInt()) }.size(with(px) { TILE_W.toDp() }, with(px) { TILE_H.toDp() })
                .graphicsLayer { scaleX = s; scaleY = s; shape = RoundedCornerShape(TILE_R); clip = true },
            contentScale = ContentScale.FillBounds,
        )
    }

    companion object {
        const val TILE_W = 250f; const val TILE_H = 150f; const val TILE_R = 30f; const val GUTTER = 48f
        const val TILE_X0 = 90f; const val TILE_Y = 776f; const val SHADOW = 60f; const val TRAY_R = 60f
        val TRAY = floatArrayOf(44f, 768f, 1832f, 230f)
    }
}

/** The scene's pictures, generated (no image assets): two backdrops with mip chains, glass textures, tiles, a shadow. */
class Art(
    val backdrops: List<List<Bitmap>>, val sharp: List<ImageBitmap>, val clear: List<ImageBitmap>, val clearW: Float, val clearH: Float,
    val tiles: List<Bitmap>, val shadow: ImageBitmap,
) {
    companion object {
        fun make(@Suppress("UNUSED_PARAMETER") w: Int): Art {
            val scenes = listOf(scene(0xFF0B1026.toInt(), intArrayOf(0xCC3A2E8F.toInt(), 0xB30E7C86.toInt(), 0xA67B2F7F.toInt()), 1), scene(0xFF140A0E.toInt(), intArrayOf(0xCCB4232C.toInt(), 0xB3E07A2F.toInt(), 0x995B1A7A.toInt()), 2))
            // Pre-blurred mip chain: 1920x1080 sharp, then each half size blurred more (box-filtered downscales, three
            // times per level, which is soft enough for a spike's scroll blur).
            val mips = scenes.map { s -> generateSequence(s) { prev -> if (prev.width <= 120) null else soften(prev) }.take(5).toList() }
            val sharp = scenes.map { it.copy(Bitmap.Config.HARDWARE, false).asImageBitmap() }
            val clear = mips.map { it[2].copy(Bitmap.Config.HARDWARE, false).asImageBitmap() }
            val tiles = (0 until 6).map { tile(it) }
            return Art(mips, sharp, clear, mips[0][2].width.toFloat(), mips[0][2].height.toFloat(), tiles, shadow().copy(Bitmap.Config.HARDWARE, false).asImageBitmap())
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
            // Fine detail, so the sharp level isn't trivially compressible: a grain of thin lines.
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
            // Glass's baked tile shadow, approximated: a blurred dark rounded rect the tile's size plus 60 px each side.
            val b = Bitmap.createBitmap(370, 270, Bitmap.Config.ARGB_8888)
            ACanvas(b).drawRoundRect(60f, 60f, 310f, 210f, 30f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AColor.argb(115, 0, 0, 0); maskFilter = android.graphics.BlurMaskFilter(28f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            })
            return b
        }
    }
}
