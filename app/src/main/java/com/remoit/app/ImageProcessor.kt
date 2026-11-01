package com.remoit.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object ImageIO {

    private fun exifRotation(path: String): Int = try {
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) { 0 }

    private fun upright(src: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return src
        val m = Matrix()
        m.postRotate(deg.toFloat())
        val r = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (r !== src) src.recycle()
        return r
    }

    /** Small preview for the editor (exif orientation applied). */
    fun loadPreview(path: String, maxSide: Int): Bitmap {
        val b = BitmapFactory.Options()
        b.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) throw IllegalArgumentException("This image could not be read.")
        var sample = 1
        while (max(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val o = BitmapFactory.Options()
        o.inSampleSize = sample
        o.inPreferredConfig = Bitmap.Config.ARGB_8888
        val bm = BitmapFactory.decodeFile(path, o) ?: throw IllegalArgumentException("This image could not be read.")
        return upright(bm, exifRotation(path))
    }

    /** Full resolution, editable, exif orientation applied. */
    fun loadFull(path: String): Bitmap {
        val o = BitmapFactory.Options()
        o.inMutable = true
        o.inPreferredConfig = Bitmap.Config.ARGB_8888
        val bm = BitmapFactory.decodeFile(path, o) ?: throw IllegalArgumentException("This image could not be read.")
        var r = upright(bm, exifRotation(path))
        if (!r.isMutable) r = r.copy(Bitmap.Config.ARGB_8888, true)
        return r
    }
}

object ImageProcessor {

    fun run(ctx: Context, path: String, spec: EditSpec, outFile: File, asJpeg: Boolean,
            progress: (Float, String, Int, String) -> Unit): IntArray {
        progress(0.05f, "Opening image", 0, "")
        val bmp = try { ImageIO.loadFull(path) } catch (e: OutOfMemoryError) {
            throw IllegalArgumentException("This image is too large for this phone's memory.")
        }
        val fw = bmp.width
        val fh = bmp.height
        val items = MaskBuilder.build(spec, fw, fh, 0)
        if (items.isEmpty()) throw IllegalArgumentException("Nothing is marked. Create a box or draw with the pen first.")

        for ((n, item) in items.withIndex()) {
            progress(0.1f + 0.8f * n / items.size, "Removing text", 0, "Area ${n + 1} of ${items.size}")
            val px = IntArray(item.w * item.h)
            bmp.getPixels(px, 0, item.w, item.x0, item.y0, item.w, item.h)
            val p = item.proc
            val cnt = px.size
            for (i in 0 until cnt) {
                val c = px[i]
                val r = ((c shr 16) and 0xFF).toFloat()
                val g = ((c shr 8) and 0xFF).toFloat()
                val b = (c and 0xFF).toFloat()
                p.ch[0][i] = 0.299f * r + 0.587f * g + 0.114f * b
                p.ch[1][i] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
                p.ch[2][i] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
            }
            if (p.process(0L)) {
                val idx = p.holeIdx
                for (k in idx.indices) {
                    val i = idx[k]
                    val y = p.res[0][i]
                    val cb = p.res[1][i] - 128f
                    val cr = p.res[2][i] - 128f
                    val r = Math.round(y + 1.402f * cr).coerceIn(0, 255)
                    val g = Math.round(y - 0.344136f * cb - 0.714136f * cr).coerceIn(0, 255)
                    val b = Math.round(y + 1.772f * cb).coerceIn(0, 255)
                    px[i] = (px[i] and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
                }
                bmp.setPixels(px, 0, item.w, item.x0, item.y0, item.w, item.h)
            }
        }

        progress(0.92f, "Saving image", 0, "")
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { out ->
            if (asJpeg) bmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
            else bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bmp.recycle()
        return intArrayOf(fw, fh)
    }
}
