package dev.glasslauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.glasslauncher.glass.Blur

/** A soft rounded-rect shadow rendered once and stretched under focused tiles. */
object TileShadow {
    val image: ImageBitmap by lazy {
        val w = 96
        val h = 64
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRoundRect(RectF(14f, 12f, w - 14f, h - 12f), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        Blur.blurInPlace(bitmap, 5)
        bitmap.asImageBitmap()
    }
}
