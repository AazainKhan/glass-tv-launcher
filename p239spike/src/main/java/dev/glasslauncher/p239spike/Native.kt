package dev.glasslauncher.p239spike

import android.graphics.Bitmap
import android.view.Surface

/** The Rust renderer (rust/, libglassgl.so). */
object Native {
    init { System.loadLibrary("glassgl") }

    @JvmStatic external fun start(surface: Surface)
    @JvmStatic external fun stop()
    /** Partial redraw (buffer age + swap with damage) on or off. */
    @JvmStatic external fun partial(on: Boolean)
    /** Texture [slot] (0, 1: backdrops; 2..7: tiles), mip [level]. */
    @JvmStatic external fun upload(slot: Int, level: Int, bitmap: Bitmap)
    /** One frame: the 6 tile scales, the cross-fade (0..1), the blur level (mip), and which backdrop is current. */
    @JvmStatic external fun frame(s0: Float, s1: Float, s2: Float, s3: Float, s4: Float, s5: Float, mix: Float, lod: Float, flip: Int)
}
