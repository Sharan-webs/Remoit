package com.remoit.app

import kotlin.math.max
import kotlin.math.min

/**
 * Texture fill (PatchMatch). Instead of blending colours, it COPIES real pieces of the picture
 * (grain, fabric, skin pores, wood, noise...) from the clean area around the hole, so the removed
 * spot does not look blurry or smooth.
 *
 * The smooth fill from [Inpaint.harmonic] is used as the starting guess and as the colour guide:
 * the final result = smooth colours + fine detail taken from the copied patches.
 *
 * Works on one ROI, three float channels (luma, cb, cr), hole == 1 means "to fill".
 */
class PatchFill(seed: Long) {

    private var rng: Long = if (seed == 0L) 88172645463325252L else seed

    private fun rnd(n: Int): Int {
        var s = rng
        s = s xor (s shl 13)
        s = s xor (s ushr 7)
        s = s xor (s shl 17)
        rng = s
        return (((s ushr 33) and 0x7FFFFFFFL) % n).toInt()
    }

    private fun dist(e: Array<FloatArray>, w: Int, r: Int, tx: Int, ty: Int, sx: Int, sy: Int, limit: Float): Float {
        var s = 0f
        val e0 = e[0]
        val e1 = e[1]
        val e2 = e[2]
        for (dy in -r..r) {
            val to = (ty + dy) * w + tx
            val so = (sy + dy) * w + sx
            for (dx in -r..r) {
                val a = e0[to + dx] - e0[so + dx]
                val b = e1[to + dx] - e1[so + dx]
                val c = e2[to + dx] - e2[so + dx]
                s += a * a + WC * (b * b + c * c)
            }
            if (s >= limit) return s
        }
        return s
    }

    /** 1 where a (2r+1)^2 window touches a hole pixel or leaves the image (= not usable as a source patch). */
    private fun windowBad(hole: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val ii = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                row += hole[y * w + x].toInt()
                ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row
            }
        }
        val bad = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < r || y < r || x >= w - r || y >= h - r) { bad[y * w + x] = 1; continue }
                val x0 = x - r
                val y0 = y - r
                val x1 = x + r + 1
                val y1 = y + r + 1
                val c = ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0]
                bad[y * w + x] = if (c > 0) 1 else 0
            }
        }
        return bad
    }

    private fun touchesHole(hole: ByteArray, w: Int, r: Int, x: Int, y: Int): Boolean {
        for (dy in -r..r) {
            val o = (y + dy) * w + x
            for (dx in -r..r) if (hole[o + dx].toInt() == 1) return true
        }
        return false
    }

    private fun boxBlur(v: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(w * h)
        val o = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var c = 0
            for (d in -r..r) { val xx = x + d; if (xx in 0 until w) { s += v[y * w + xx]; c++ } }
            t[y * w + x] = s / c
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var c = 0
            for (d in -r..r) { val yy = y + d; if (yy in 0 until h) { s += t[yy * w + x]; c++ } }
            o[y * w + x] = s / c
        }
        return o
    }

    /**
     * [init]: complete image, hole pixels already hold the smooth estimate.
     * [out]: receives the complete result (known pixels unchanged).
     * [fast]: fewer passes (used for video frames). Returns false if there was nothing usable to copy from.
     */
    fun run(init: Array<FloatArray>, hole0: ByteArray, w0: Int, h0: Int, out: Array<FloatArray>, fast: Boolean, detail: Float = 1f): Boolean {
        val r = 3
        val maxLv = 3

        // ---- pyramid
        val imgs = ArrayList<Array<FloatArray>>()
        val holes = ArrayList<ByteArray>()
        val dimW = ArrayList<Int>()
        val dimH = ArrayList<Int>()
        imgs.add(Array(3) { init[it].copyOf() })
        holes.add(hole0.copyOf())
        dimW.add(w0); dimH.add(h0)
        while (imgs.size < maxLv) {
            val p = imgs[imgs.size - 1]
            val ph = holes[holes.size - 1]
            val pw = dimW[dimW.size - 1]
            val phh = dimH[dimH.size - 1]
            val nw = (pw + 1) / 2
            val nh = (phh + 1) / 2
            if (min(nw, nh) < 28) break
            val q = Array(3) { FloatArray(nw * nh) }
            val qh = ByteArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                var s0 = 0f; var s1 = 0f; var s2 = 0f
                var hh: Byte = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val xx = min(pw - 1, 2 * x + dx)
                    val yy = min(phh - 1, 2 * y + dy)
                    val i = yy * pw + xx
                    s0 += p[0][i]; s1 += p[1][i]; s2 += p[2][i]
                    if (ph[i].toInt() == 1) hh = 1
                }
                q[0][y * nw + x] = s0 / 4f; q[1][y * nw + x] = s1 / 4f; q[2][y * nw + x] = s2 / 4f
                qh[y * nw + x] = hh
            }
            imgs.add(q); holes.add(qh); dimW.add(nw); dimH.add(nh)
        }

        var nnf: IntArray? = null
        var prevW = 0
        var prevH = 0
        var prevEst: Array<FloatArray>? = null

        for (lv in imgs.size - 1 downTo 0) {
            val e = imgs[lv]
            val hole = holes[lv]
            val w = dimW[lv]
            val h = dimH[lv]
            val n = w * h

            // start estimate: upsample the coarser result into the hole
            val pe = prevEst
            if (pe != null) {
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    if (hole[i].toInt() == 0) continue
                    var fx = (x + 0.5f) / 2f - 0.5f
                    var fy = (y + 0.5f) / 2f - 0.5f
                    fx = max(0f, min((prevW - 1).toFloat(), fx))
                    fy = max(0f, min((prevH - 1).toFloat(), fy))
                    val x0 = fx.toInt()
                    val y0 = fy.toInt()
                    val x1 = min(x0 + 1, prevW - 1)
                    val y1 = min(y0 + 1, prevH - 1)
                    val tx = fx - x0
                    val ty = fy - y0
                    for (c in 0..2) {
                        val a = pe[c][y0 * prevW + x0] * (1 - tx) + pe[c][y0 * prevW + x1] * tx
                        val b = pe[c][y1 * prevW + x0] * (1 - tx) + pe[c][y1 * prevW + x1] * tx
                        e[c][i] = a * (1 - ty) + b * ty
                    }
                }
            }

            val bad = windowBad(hole, w, h, r)
            var srcCount = 0
            for (i in 0 until n) if (bad[i].toInt() == 0) srcCount++
            if (srcCount < 20) return false
            val srcList = IntArray(srcCount)
            var k = 0
            for (i in 0 until n) if (bad[i].toInt() == 0) srcList[k++] = i

            // target centres: window touches the hole, centre inside the image
            val isT = BooleanArray(n)
            var tc = 0
            for (y in r until h - r) for (x in r until w - r) {
                if (touchesHole(hole, w, r, x, y)) { isT[y * w + x] = true; tc++ }
            }
            if (tc == 0) return false
            val tl = IntArray(tc)
            k = 0
            for (i in 0 until n) if (isT[i]) tl[k++] = i

            // nnf: from the coarser level, or random
            val nn = IntArray(n) { -1 }
            val nd = FloatArray(n)
            val old = nnf
            for (t in 0 until tc) {
                val i = tl[t]
                val x = i % w
                val y = i / w
                var s = -1
                if (old != null) {
                    val cx = min(x / 2, prevW - 1)
                    val cy = min(y / 2, prevH - 1)
                    val ps = old[cy * prevW + cx]
                    if (ps >= 0) {
                        val sx = (ps % prevW) * 2 + (x and 1)
                        val sy = (ps / prevW) * 2 + (y and 1)
                        if (sx < w && sy < h && bad[sy * w + sx].toInt() == 0) s = sy * w + sx
                    }
                }
                if (s < 0) s = srcList[rnd(srcCount)]
                nn[i] = s
                nd[i] = dist(e, w, r, x, y, s % w, s / w, Float.MAX_VALUE)
            }

            val emIters = if (lv == 0) (if (fast) 2 else 4) else (if (fast) 2 else 3)
            val pmIters = if (fast) 3 else 4
            val acc = Array(3) { FloatArray(n) }
            val wsum = FloatArray(n)
            var holeCount = 0
            for (i in 0 until n) if (hole[i].toInt() == 1) holeCount++
            val hl = IntArray(holeCount)
            k = 0
            for (i in 0 until n) if (hole[i].toInt() == 1) hl[k++] = i
            val np = (2 * r + 1) * (2 * r + 1)

            for (em in 0 until emIters) {
                // ---- patchmatch
                for (it in 0 until pmIters) {
                    val fwd = (it % 2 == 0)
                    val step = if (fwd) 1 else -1
                    for (tt in 0 until tc) {
                        val i = if (fwd) tl[tt] else tl[tc - 1 - tt]
                        val x = i % w
                        val y = i / w
                        var best = nn[i]
                        var bd = nd[i]
                        // propagate from the previous pixel in scan order (horizontal)
                        var j = i - step
                        if (x - step in 0 until w && isT[j] && nn[j] >= 0) {
                            val s = nn[j] + step
                            val sx = s % w
                            val sy = s / w
                            if (sx - r >= 0 && sx + r < w && s in 0 until n && bad[s].toInt() == 0) {
                                val d = dist(e, w, r, x, y, sx, sy, bd)
                                if (d < bd) { bd = d; best = s }
                            }
                        }
                        // vertical
                        j = i - step * w
                        if (y - step in 0 until h && isT[j] && nn[j] >= 0) {
                            val s = nn[j] + step * w
                            if (s in 0 until n && bad[s].toInt() == 0) {
                                val d = dist(e, w, r, x, y, s % w, s / w, bd)
                                if (d < bd) { bd = d; best = s }
                            }
                        }
                        // random search around the current best, shrinking radius
                        var rad = max(w, h) / 2
                        val bx = best % w
                        val by = best / w
                        while (rad >= 1) {
                            val sx = bx + rnd(2 * rad + 1) - rad
                            val sy = by + rnd(2 * rad + 1) - rad
                            if (sx >= r && sx < w - r && sy >= r && sy < h - r && bad[sy * w + sx].toInt() == 0) {
                                val d = dist(e, w, r, x, y, sx, sy, bd)
                                if (d < bd) { bd = d; best = sy * w + sx }
                            }
                            rad /= 2
                        }
                        nn[i] = best
                        nd[i] = bd
                    }
                }

                // ---- vote: every hole pixel = weighted mean of what the overlapping patches say
                for (q in 0 until holeCount) {
                    val i = hl[q]
                    acc[0][i] = 0f; acc[1][i] = 0f; acc[2][i] = 0f; wsum[i] = 0f
                }
                for (tt in 0 until tc) {
                    val i = tl[tt]
                    val x = i % w
                    val y = i / w
                    val s = nn[i]
                    val wg = 1f / (1f + (nd[i] / np) / 25f)
                    for (dy in -r..r) for (dx in -r..r) {
                        val p = (y + dy) * w + x + dx
                        if (hole[p].toInt() == 0) continue
                        val sp = s + dy * w + dx
                        acc[0][p] += wg * e[0][sp]; acc[1][p] += wg * e[1][sp]; acc[2][p] += wg * e[2][sp]
                        wsum[p] += wg
                    }
                }
                val sharpLast = (lv == 0 && em == emIters - 1)
                for (q in 0 until holeCount) {
                    val i = hl[q]
                    if (wsum[i] <= 0f) continue
                    for (c in 0..2) {
                        var v = acc[c][i] / wsum[i]
                        if (sharpLast && nn[i] >= 0) v = 0.55f * e[c][nn[i]] + 0.45f * v
                        e[c][i] = v
                    }
                }
                // distances changed because the target pixels changed
                for (tt in 0 until tc) {
                    val i = tl[tt]
                    val s = nn[i]
                    nd[i] = dist(e, w, r, i % w, i / w, s % w, s / w, Float.MAX_VALUE)
                }
            }
            nnf = nn
            prevEst = e
            prevW = w
            prevH = h
        }

        // keep the smooth fill's colours, take only the fine detail from the copied patches
        val e0 = prevEst ?: return false
        for (c in 0..2) {
            val bl = boxBlur(e0[c], w0, h0, 3)
            val dk = (if (c == 0) 1f else 0.6f) * detail
            for (i in 0 until w0 * h0) {
                out[c][i] = if (hole0[i].toInt() == 1) init[c][i] + (e0[c][i] - bl[i]) * dk else init[c][i]
            }
        }
        return true
    }

    companion object {
        private const val WC = 0.35f
    }
}
