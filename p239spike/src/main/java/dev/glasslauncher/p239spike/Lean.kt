package dev.glasslauncher.p239spike

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/** The lean renderer (rust-lean/, libglasslean.so): every call on its one GL thread, which this object owns. */
object Lean {
    init { System.loadLibrary("glasslean") }
    @JvmStatic external fun init(surface: Surface): Boolean
    @JvmStatic external fun upload(slot: Int, level: Int, bitmap: Bitmap)
    @JvmStatic external fun frame(s0: Float, s1: Float, s2: Float, s3: Float, s4: Float, s5: Float, mix: Float, lod: Float, flip: Int)
    @JvmStatic external fun release()

    private val thread = HandlerThread("glass-gl").apply { start() }
    val gl = Handler(thread.looper)
    @Volatile private var pending: FloatArray? = null

    /** Latest values win: a frame already queued just picks up the newest. */
    fun post(v: FloatArray) {
        val queued = pending != null
        pending = v
        if (!queued) gl.post { pending?.let { p -> pending = null; frame(p[0], p[1], p[2], p[3], p[4], p[5], p[6], 0f, p[7].toInt()) } }
    }
}
