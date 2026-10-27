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
    /** Bit 0: grid labels drawn natively from the atlas (slot 8). Bit 1: skew marker bars. */
    @JvmStatic external fun flags(flags: Int)

    /** One component of a cover's pose (0 x, 1 rotationY, 2 scale, 3 z, 4 alpha, 5 visible) - the ported geometry. */
    @JvmStatic external fun poseField(offset: Float, field: Int): Float
    /** Texture [slot] (0, 1: backdrops; 2..7: tile art; 8: label atlas), mip [level]; [etc2] encodes it first. */
    @JvmStatic external fun upload(slot: Int, level: Int, bitmap: Bitmap, etc2: Boolean)
    /** One frame: scroll (px), scroll blur (mip level), cross-fade, current backdrop, and the two animating tiles. */
    @JvmStatic external fun frame(scroll: Float, lod: Float, mix: Float, flip: Int, a: Int, sa: Float, b: Int, sb: Float)
}
