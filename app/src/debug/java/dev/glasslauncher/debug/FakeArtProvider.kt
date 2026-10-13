package dev.glasslauncher.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** Debug only (P147): a cover served after a configurable delay, so the art path's load can be watched on the emulator. */
class FakeArtProvider : ContentProvider() {
    companion object { @Volatile var delayMs = 2_000L }

    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        Thread.sleep(delayMs)
        val f = File(context!!.cacheDir, "fake-cover-${uri.lastPathSegment}.png")
        val b = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val seed = (uri.lastPathSegment?.hashCode() ?: 0)
        Canvas(b).drawRect(0f, 0f, 640f, 640f, Paint().apply {
            shader = LinearGradient(0f, 0f, 640f, 640f, Color.HSVToColor(floatArrayOf((seed and 0x1ff) % 360f, 0.7f, 0.9f)), Color.HSVToColor(floatArrayOf((seed % 360 + 120) % 360f, 0.8f, 0.5f)), Shader.TileMode.CLAMP)
        })
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String = "image/png"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
