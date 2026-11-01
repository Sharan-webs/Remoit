package com.remoit.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.geometry.Offset
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A box the user created. Coordinates are 0..1 of the picture as the user sees it. */
data class MarkBox(
    val id: Long,
    val l: Float, val t: Float, val r: Float, val b: Float,
    val startMs: Long = 0L,
    val endMs: Long = Long.MAX_VALUE
)

/** A pen stroke. Points are 0..1 of the picture, width is a fraction of the picture width. */
class MarkStroke(val pts: List<Offset>, val width: Float)

class EditSpec(val boxes: List<MarkBox>, val strokes: List<MarkStroke>, val smartBoxes: Boolean = true) {
    fun isEmpty() = boxes.isEmpty() && strokes.isEmpty()
}

/** One area to clean, in the real (unrotated) pixels of the frame. */
class MaskItem(
    val x0: Int, val y0: Int, val w: Int, val h: Int,
    val mask: ByteArray,
    val refine: Boolean,
    val startUs: Long, val endUs: Long
) {
    val proc = ItemProcessor(this)
}

object MaskBuilder {

    /** Maps a point of the displayed picture (0..1) to real frame pixels, undoing the video rotation. */
    private fun mapPt(u: Float, v: Float, rot: Int, fw: Int, fh: Int): FloatArray {
        val dw = if (rot == 90 || rot == 270) fh else fw
        val dh = if (rot == 90 || rot == 270) fw else fh
        val px = u * dw
        val py = v * dh
        return when (rot) {
            90 -> floatArrayOf(py, fh - px)
            180 -> floatArrayOf(fw - px, fh - py)
            270 -> floatArrayOf(fw - py, px)
            else -> floatArrayOf(px, py)
        }
    }

    /** Even-aligned region of interest, so every chroma block lies completely inside it. */
    private fun alignRoi(x0: Int, y0: Int, x1: Int, y1: Int, fw: Int, fh: Int): IntArray {
        val ax0 = max(0, x0) and 1.inv()
        val ay0 = max(0, y0) and 1.inv()
        var ax1 = min(fw, x1)
        var ay1 = min(fh, y1)
        if ((ax1 and 1) == 1 && ax1 < fw) ax1++
        if ((ay1 and 1) == 1 && ay1 < fh) ay1++
        return intArrayOf(ax0, ay0, max(ax1, ax0 + 2).coerceAtMost(fw), max(ay1, ay0 + 2).coerceAtMost(fh))
    }

    fun build(spec: EditSpec, fw: Int, fh: Int, rot: Int): List<MaskItem> {
        val out = ArrayList<MaskItem>()
        val dispW = if (rot == 90 || rot == 270) fh else fw

        // ---- boxes
        for (b in spec.boxes) {
            val p1 = mapPt(b.l, b.t, rot, fw, fh)
            val p2 = mapPt(b.r, b.b, rot, fw, fh)
            val bx0 = floor(min(p1[0], p2[0])).toInt().coerceIn(0, fw - 1)
            val by0 = floor(min(p1[1], p2[1])).toInt().coerceIn(0, fh - 1)
            val bx1 = ceil(max(p1[0], p2[0])).toInt().coerceIn(bx0 + 1, fw)
            val by1 = ceil(max(p1[1], p2[1])).toInt().coerceIn(by0 + 1, fh)
            val pad = (min(bx1 - bx0, by1 - by0) * 0.5f).toInt().coerceIn(14, 72)
            val roi = alignRoi(bx0 - pad, by0 - pad, bx1 + pad, by1 + pad, fw, fh)
            val rw = roi[2] - roi[0]
            val rh = roi[3] - roi[1]
            if (rw < 4 || rh < 4) continue
            val mask = ByteArray(rw * rh)
            for (y in (by0 - roi[1]) until (by1 - roi[1])) {
                if (y < 0 || y >= rh) continue
                for (x in (bx0 - roi[0]) until (bx1 - roi[0])) {
                    if (x < 0 || x >= rw) continue
                    mask[y * rw + x] = 1
                }
            }
            val sUs = b.startMs * 1000L
            val eUs = if (b.endMs == Long.MAX_VALUE) Long.MAX_VALUE else b.endMs * 1000L
            out.add(MaskItem(roi[0], roi[1], rw, rh, mask, spec.smartBoxes, sUs, eUs))
        }

        // ---- pen strokes: group strokes that are close together
        class Group(var x0: Float, var y0: Float, var x1: Float, var y1: Float, val ids: ArrayList<Int>)

        val mapped = ArrayList<List<FloatArray>>()
        val groups = ArrayList<Group>()
        for ((si, s) in spec.strokes.withIndex()) {
            val pts = s.pts.map { mapPt(it.x, it.y, rot, fw, fh) }
            mapped.add(pts)
            if (pts.isEmpty()) continue
            val r = max(2f, s.width * dispW / 2f) + 2f
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (p in pts) {
                minX = min(minX, p[0]); maxX = max(maxX, p[0])
                minY = min(minY, p[1]); maxY = max(maxY, p[1])
            }
            groups.add(Group(minX - r, minY - r, maxX + r, maxY + r, arrayListOf(si)))
        }
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in 0 until groups.size) {
                for (j in i + 1 until groups.size) {
                    val a = groups[i]
                    val c = groups[j]
                    val m = 16f
                    if (a.x0 - m < c.x1 && c.x0 - m < a.x1 && a.y0 - m < c.y1 && c.y0 - m < a.y1) {
                        a.x0 = min(a.x0, c.x0); a.y0 = min(a.y0, c.y0)
                        a.x1 = max(a.x1, c.x1); a.y1 = max(a.y1, c.y1)
                        a.ids.addAll(c.ids)
                        groups.removeAt(j)
                        merged = true
                        break@loop
                    }
                }
            }
        }
        for (g in groups) {
            val roi = alignRoi(floor(g.x0).toInt() - 20, floor(g.y0).toInt() - 20, ceil(g.x1).toInt() + 20, ceil(g.y1).toInt() + 20, fw, fh)
            val rw = roi[2] - roi[0]
            val rh = roi[3] - roi[1]
            if (rw < 4 || rh < 4) continue
            val bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val paint = Paint()
            paint.isAntiAlias = false
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.WHITE
            val dot = Paint()
            dot.isAntiAlias = false
            dot.style = Paint.Style.FILL
            dot.color = Color.WHITE
            for (si in g.ids) {
                val s = spec.strokes[si]
                val pts = mapped[si]
                if (pts.isEmpty()) continue
                val sw = max(3f, s.width * dispW)
                paint.strokeWidth = sw
                if (pts.size == 1) {
                    cv.drawCircle(pts[0][0] - roi[0], pts[0][1] - roi[1], sw / 2f, dot)
                } else {
                    val path = Path()
                    path.moveTo(pts[0][0] - roi[0], pts[0][1] - roi[1])
                    for (k in 1 until pts.size) path.lineTo(pts[k][0] - roi[0], pts[k][1] - roi[1])
                    cv.drawPath(path, paint)
                }
            }
            val px = IntArray(rw * rh)
            bmp.getPixels(px, 0, rw, 0, 0, rw, rh)
            bmp.recycle()
            val mask = ByteArray(rw * rh)
            for (i in px.indices) if ((px[i] ushr 24) > 100) mask[i] = 1
            out.add(MaskItem(roi[0], roi[1], rw, rh, mask, false, 0L, Long.MAX_VALUE))
        }
        return out
    }
}
