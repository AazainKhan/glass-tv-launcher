package dev.glasslauncher.apps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/**
 * Rendered tiles kept on disk, so a cold start decodes a small lossless image per tile instead of re-running
 * the render (a banner decode and crop, an icon wash and blur) for every app. A tile's file name is a hash of
 * everything that went into it (see [TileArt.diskKey]), so a changed app, icon, or pack is simply a different
 * file; old ones are trimmed oldest-first. Only the tile's own pixels are stored.
 */
class TileDiskCache(private val dir: File, private val maxFiles: Int = MAX_FILES) {

    private fun file(key: String) = File(dir, hash(key) + ".png")

    fun get(key: String): Bitmap? {
        val f = file(key)
        if (!f.isFile) return null
        return runCatching {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }.getOrNull().also { if (it == null) f.delete() }
    }

    fun put(key: String, bitmap: Bitmap) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, hash(key) + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Renamed into place, so a reader never sees a half-written tile.
            if (!tmp.renameTo(file(key))) tmp.delete()
        }
    }

    /** Keeps the newest [maxFiles] tiles (and clears stray partial writes). */
    fun trim() {
        val files = dir.listFiles() ?: return
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        val tiles = files.filter { it.name.endsWith(".png") }.sortedByDescending { it.lastModified() }
        tiles.drop(maxFiles).forEach { it.delete() }
    }

    fun count() = dir.listFiles { f -> f.name.endsWith(".png") }?.size ?: 0

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_FILES = 400
    }
}
