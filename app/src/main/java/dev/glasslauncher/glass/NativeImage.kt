package dev.glasslauncher.glass

import android.graphics.Bitmap

/**
 * Native colour matrices for the backdrop bake (rust/, built by scripts/rust-build into libglassimg.so). Skia runs a
 * ColorMatrixColorFilter on its slow per-pixel path; these do the same matrices 2-5x faster on the Fire TV Stick 4K
 * (rom research: rust-in-glass). [ok] is false where the library doesn't load (JVM tests, an ABI it isn't built for):
 * callers then keep their Skia path. Each call returns false, bitmap untouched, unless it is an ARGB_8888 bitmap.
 */
internal object NativeImage {
    val ok: Boolean = runCatching { System.loadLibrary("glassimg") }
        // Fire OS drops Log.d from apps; a missing library on a device is worth one line.
        .onFailure { runCatching { android.util.Log.i("Glass", "native image kernels unavailable: $it") } }
        .isSuccess

    /** Android's ColorMatrix.setSaturation, in place. */
    @JvmStatic external fun saturate(bitmap: Bitmap, saturation: Float): Boolean

    /** Blur.legible's matrix (c * scale + offset on RGB), in place. */
    @JvmStatic external fun scaleOffset(bitmap: Bitmap, scale: Float, offset: Float): Boolean
}
