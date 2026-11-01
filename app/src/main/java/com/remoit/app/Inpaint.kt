package com.remoit.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * remoit inpainting core.
 *
 * Works on a small region of interest (ROI) of one frame, as three full-resolution float channels
 * (channel 0 = luma-like, 1 and 2 = colour). It never touches the rest of the frame.
 *
 * Pipeline for one region:
 *  1. (boxes only) estimate the clean background under the box from its surroundings, compare it with the real pixels
 *     and keep only the pixels that really are text / logo. Everything else inside the box stays untouched.
 *  2. Fill those pixels from the colours around them (smooth multi-scale + relaxation fill, so gradients continue).
 *  3. Bring the texture back: structure is mirrored from the nearest clean pixels, and grain matching the
 *     surrounding noise is added, so the patch does not look smooth or "erased".
 *  4. Blend the border softly.
 *  5. Static scenes reuse the previous result (perfectly stable, much faster). Moving scenes recompute.
 */
class ItemProcessor(private val item: MaskItem) {
    val w = item.w
    val h = item.h
    private val n = w * h

    /** Input ROI, filled by the caller before process(). */
    val ch = Array(3) { FloatArray(n) }

    /** Output ROI (valid for every pixel after a process() that returned true). */
    val res = Array(3) { FloatArray(n) }

    /** Pixels that were changed (alpha > 0). */
    var holeIdx = IntArray(0)
        private set

    private var alpha = FloatArray(0)
    private val fill = Array(3) { FloatArray(n) }
    private val prevFill = Array(3) { FloatArray(n) }
    private var prevHole = BooleanArray(0)
    private val tmp = Array(3) { FloatArray(n) }
    private val diff = FloatArray(n)
    private val gain = FloatArray(n)
    private val sigma = FloatArray(3)

    private var active = false
    private var haveResult = false
    private var anchorSig: FloatArray? = null
    private val sigIdx: IntArray

    /** One marker per 2x2 chroma block, used by the video writer. */
    val blockMark = ByteArray(((w + 1) / 2) * ((h + 1) / 2))

    private var rng = 0x2545F4914F6CDD1DL

    init {
        var step = max(1, n / 900)
        if (step % 2 == 0) step++
        val list = ArrayList<Int>()
        var i = 0
        while (i < n && list.size < 1200) { list.add(i); i += step }
        sigIdx = list.toIntArray()
    }

    /** True if something must be blended into the frame. Call after filling [ch]. */
    fun process(frameIdx: Long): Boolean {
        val sig = FloatArray(sigIdx.size)
        for (k in sigIdx.indices) sig[k] = ch[0][sigIdx[k]]
        val anchor = anchorSig
        val reuse = haveResult && anchor != null && mad(sig, anchor) < REUSE_T
        if (!reuse) {
            var smooth = false
            if (haveResult && active) {
                for (c in 0..2) System.arraycopy(fill[c], 0, prevFill[c], 0, n)
                if (prevHole.size != n) prevHole = BooleanArray(n)
                for (i in 0 until n) prevHole[i] = alpha[i] > 0f
                smooth = anchor != null && mad(sig, anchor) < 7f
            }
            recompute()
            if (active && smooth) {
                for (k in holeIdx.indices) {
                    val i = holeIdx[k]
                    if (prevHole[i]) for (c in 0..2) fill[c][i] = prevFill[c][i] * 0.4f + fill[c][i] * 0.6f
                }
            }
            anchorSig = sig
            haveResult = true
        }
        if (!active) return false
        composite(frameIdx)
        return true
    }

    private fun recompute() {
        active = false
        holeIdx = IntArray(0)
        val base = item.mask
        val core: ByteArray

        if (item.refine) {
            // 1. what would the box look like without text?
            if (!Inpaint.harmonic(ch, base, w, h, tmp, 14)) return
            val hist = IntArray(256)
            var cnt = 0
            for (i in 0 until n) {
                if (base[i].toInt() == 1) {
                    val dy = abs(ch[0][i] - tmp[0][i])
                    val du = abs(ch[1][i] - tmp[1][i])
                    val dv = abs(ch[2][i] - tmp[2][i])
                    val d = max(dy, max(du, dv) * 0.9f)
                    diff[i] = d
                    hist[min(255, d.toInt())]++
                    cnt++
                }
            }
            if (cnt == 0) return
            var acc = 0
            var med = 0
            while (med < 255 && acc + hist[med] < cnt / 2) { acc += hist[med]; med++ }
            val thr = (med * 1.8f + 4f).coerceIn(6f, 38f)
            val text = ByteArray(n)
            var tc = 0
            for (i in 0 until n) {
                if (base[i].toInt() == 1 && diff[i] > thr) { text[i] = 1; tc++ }
            }
            // no text found in this frame: leave the frame alone
            if (tc < max(6, (cnt * 0.0012f).toInt())) return
            val grown = Inpaint.dilate(text, w, h, 3)
            var gc = 0
            for (i in 0 until n) if (grown[i].toInt() == 1 && base[i].toInt() == 1) gc++
            core = if (gc.toFloat() / cnt > 0.9f) base else grown
        } else {
            core = base
        }

        val alphaCore = Inpaint.dilate(core, w, h, 1)
        val solve = Inpaint.dilate(core, w, h, 2)

        var holeCount = 0
        for (i in 0 until n) if (solve[i].toInt() == 1) holeCount++
        val iters = if (holeCount < 600) 12 else 26
        if (!Inpaint.harmonic(ch, solve, w, h, fill, iters)) return

        addTexture(solve)

        val a = Inpaint.blur3(alphaCore, w, h)
        var cntA = 0
        for (i in 0 until n) { if (solve[i].toInt() == 0) a[i] = 0f; if (a[i] > 0.001f) cntA++ }
        val idx = IntArray(cntA)
        var k = 0
        for (i in 0 until n) if (a[i] > 0.001f) idx[k++] = i
        alpha = a
        holeIdx = idx
        active = cntA > 0
    }

    private fun addTexture(solve: ByteArray) {
        val known = ByteArray(n)
        for (i in 0 until n) known[i] = if (solve[i].toInt() == 0) 1 else 0
        val blur = Array(3) { c -> Inpaint.boxBlurNorm(ch[c], known, w, h, 2) }

        // how noisy is the picture around the patch?
        val band = Inpaint.dilate(solve, w, h, 8)
        val sq = DoubleArray(3)
        var bc = 0
        for (i in 0 until n) {
            if (band[i].toInt() == 1 && known[i].toInt() == 1) {
                for (c in 0..2) { val d = ch[c][i] - blur[c][i]; sq[c] += (d * d).toDouble() }
                bc++
            }
        }
        for (c in 0..2) sigma[c] = if (bc > 20) min(8.0, sqrt(sq[c] / bc)).toFloat() else 0f

        // mirror fine structure from the nearest clean pixels
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (solve[i].toInt() == 0) continue
                val q = mirrorSource(x, y, solve)
                if (q >= 0) {
                    fill[0][i] += ch[0][q] - blur[0][q]
                    fill[1][i] += (ch[1][q] - blur[1][q]) * 0.6f
                    fill[2][i] += (ch[2][q] - blur[2][q]) * 0.6f
                    gain[i] = 0.5f
                } else {
                    gain[i] = 1f
                }
            }
        }
    }

    /** Index of a clean pixel mirrored across the nearest edge of the hole, or -1. */
    private fun mirrorSource(x: Int, y: Int, solve: ByteArray): Int {
        var bestK = MAX_MIRROR + 1
        var best = -1
        // left
        var k = 1
        while (x - k >= 0 && solve[y * w + x - k].toInt() == 1 && k <= MAX_MIRROR) k++
        if (x - k >= 0 && k <= MAX_MIRROR) {
            val qx = x - 2 * k + 1
            if (qx >= 0 && solve[y * w + qx].toInt() == 0 && k < bestK) { bestK = k; best = y * w + qx }
        }
        // right
        k = 1
        while (x + k < w && solve[y * w + x + k].toInt() == 1 && k <= MAX_MIRROR) k++
        if (x + k < w && k <= MAX_MIRROR) {
            val qx = x + 2 * k - 1
            if (qx < w && solve[y * w + qx].toInt() == 0 && k < bestK) { bestK = k; best = y * w + qx }
        }
        // up
        k = 1
        while (y - k >= 0 && solve[(y - k) * w + x].toInt() == 1 && k <= MAX_MIRROR) k++
        if (y - k >= 0 && k <= MAX_MIRROR) {
            val qy = y - 2 * k + 1
            if (qy >= 0 && solve[qy * w + x].toInt() == 0 && k < bestK) { bestK = k; best = qy * w + x }
        }
        // down
        k = 1
        while (y + k < h && solve[(y + k) * w + x].toInt() == 1 && k <= MAX_MIRROR) k++
        if (y + k < h && k <= MAX_MIRROR) {
            val qy = y + 2 * k - 1
            if (qy < h && solve[qy * w + x].toInt() == 0 && k < bestK) { bestK = k; best = qy * w + x }
        }
        return best
    }

    private fun composite(frameIdx: Long) {
        for (c in 0..2) System.arraycopy(ch[c], 0, res[c], 0, n)
        rng = ((frameIdx + 1) * 0x2545F4914F6CDD1DL) xor 0x5DEECE66DL
        if (rng == 0L) rng = 88172645463325252L
        for (k in holeIdx.indices) {
            val i = holeIdx[k]
            val a = alpha[i]
            val g = gain[i]
            for (c in 0..2) {
                var v = fill[c][i]
                if (sigma[c] > 0f) v += gaussian() * sigma[c] * g
                if (v < 0f) v = 0f else if (v > 255f) v = 255f
                res[c][i] = ch[c][i] * (1f - a) + v * a
            }
        }
    }

    private fun uniform(): Float {
        var s = rng
        s = s xor (s shl 13)
        s = s xor (s ushr 7)
        s = s xor (s shl 17)
        rng = s
        return ((s ushr 40).toInt() and 0xFFFFFF) / 16777216f
    }

    private fun gaussian(): Float = (uniform() + uniform() + uniform() + uniform() - 2f) * 1.7320508f

    private fun mad(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val m = min(a.size, b.size)
        for (i in 0 until m) s += abs(a[i] - b[i])
        return if (m == 0) 0f else s / m
    }

    companion object {
        private const val REUSE_T = 2.0f
        private const val MAX_MIRROR = 20
    }
}

private class Lv(val w: Int, val h: Int) {
    val v = Array(3) { FloatArray(w * h) }
    val wt = FloatArray(w * h)
}

object Inpaint {

    /**
     * Fills every pixel where hole == 1 from the pixels where hole == 0.
     * Multi-scale push-pull gives a smooth start, relaxation makes the fill continue gradients smoothly.
     * out receives the complete result (known pixels copied unchanged). Returns false if nothing is known.
     */
    fun harmonic(src: Array<FloatArray>, hole: ByteArray, w: Int, h: Int, out: Array<FloatArray>, iters: Int): Boolean {
        val n = w * h
        val l0 = Lv(w, h)
        var known = 0
        for (i in 0 until n) {
            if (hole[i].toInt() == 0) {
                l0.wt[i] = 1f
                l0.v[0][i] = src[0][i]; l0.v[1][i] = src[1][i]; l0.v[2][i] = src[2][i]
                known++
            }
        }
        if (known == 0) return false

        val levels = ArrayList<Lv>()
        levels.add(l0)
        while (true) {
            val p = levels[levels.size - 1]
            if ((p.w <= 2 && p.h <= 2) || levels.size >= 10) break
            val q = Lv((p.w + 1) / 2, (p.h + 1) / 2)
            for (y in 0 until p.h) {
                val qy = (y shr 1) * q.w
                val py = y * p.w
                for (x in 0 until p.w) {
                    val i = py + x
                    val wt = p.wt[i]
                    if (wt != 0f) {
                        val j = qy + (x shr 1)
                        q.wt[j] += wt
                        q.v[0][j] += p.v[0][i]; q.v[1][j] += p.v[1][i]; q.v[2][j] += p.v[2][i]
                    }
                }
            }
            levels.add(q)
        }

        val mean = FloatArray(3)
        val top = levels[levels.size - 1]
        var tw = 0f
        for (i in top.wt.indices) {
            tw += top.wt[i]
            for (c in 0..2) mean[c] += top.v[c][i]
        }
        if (tw <= 0f) return false
        for (c in 0..2) mean[c] /= tw

        var parent: Array<FloatArray>? = null
        var pw = 0
        var ph = 0
        for (li in levels.size - 1 downTo 0) {
            val lv = levels[li]
            val cur = Array(3) { FloatArray(lv.w * lv.h) }
            val par = parent
            for (y in 0 until lv.h) {
                for (x in 0 until lv.w) {
                    val i = y * lv.w + x
                    val wt = lv.wt[i]
                    if (wt > 1e-6f) {
                        for (c in 0..2) cur[c][i] = lv.v[c][i] / wt
                    } else if (par == null) {
                        for (c in 0..2) cur[c][i] = mean[c]
                    } else {
                        val fx = (x + 0.5f) / 2f - 0.5f
                        val fy = (y + 0.5f) / 2f - 0.5f
                        for (c in 0..2) cur[c][i] = bilerp(par[c], pw, ph, fx, fy)
                    }
                }
            }
            parent = cur
            pw = lv.w
            ph = lv.h
        }
        val first = parent ?: return false
        for (i in 0 until n) {
            if (hole[i].toInt() == 0) {
                out[0][i] = src[0][i]; out[1][i] = src[1][i]; out[2][i] = src[2][i]
            } else {
                out[0][i] = first[0][i]; out[1][i] = first[1][i]; out[2][i] = first[2][i]
            }
        }

        var cntH = 0
        for (i in 0 until n) if (hole[i].toInt() == 1) cntH++
        if (cntH == 0) return true
        val hi = IntArray(cntH)
        var kk = 0
        for (i in 0 until n) if (hole[i].toInt() == 1) hi[kk++] = i

        val o0 = out[0]
        val o1 = out[1]
        val o2 = out[2]
        for (iter in 0 until iters) {
            val fwd = iter % 2 == 0
            for (k0 in 0 until cntH) {
                val i = if (fwd) hi[k0] else hi[cntH - 1 - k0]
                val x = i % w
                val y = i / w
                var cnt = 0
                var s0 = 0f
                var s1 = 0f
                var s2 = 0f
                if (x > 0) { val j = i - 1; s0 += o0[j]; s1 += o1[j]; s2 += o2[j]; cnt++ }
                if (x < w - 1) { val j = i + 1; s0 += o0[j]; s1 += o1[j]; s2 += o2[j]; cnt++ }
                if (y > 0) { val j = i - w; s0 += o0[j]; s1 += o1[j]; s2 += o2[j]; cnt++ }
                if (y < h - 1) { val j = i + w; s0 += o0[j]; s1 += o1[j]; s2 += o2[j]; cnt++ }
                if (cnt == 0) continue
                o0[i] += 1.5f * (s0 / cnt - o0[i])
                o1[i] += 1.5f * (s1 / cnt - o1[i])
                o2[i] += 1.5f * (s2 / cnt - o2[i])
            }
        }
        return true
    }

    private fun bilerp(a: FloatArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        val x = fx.coerceIn(0f, (w - 1).toFloat())
        val y = fy.coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = x - x0
        val ty = y - y0
        val top = a[y0 * w + x0] * (1f - tx) + a[y0 * w + x1] * tx
        val bot = a[y1 * w + x0] * (1f - tx) + a[y1 * w + x1] * tx
        return top * (1f - ty) + bot * ty
    }

    /** Square dilation of a 0/1 mask by radius r (linear time). */
    fun dilate(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        if (r <= 0) return src.copyOf()
        val tmpA = ByteArray(w * h)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val o = y * w
            var cnt = 0
            for (x in 0..min(r, w - 1)) cnt += src[o + x].toInt()
            for (x in 0 until w) {
                tmpA[o + x] = if (cnt > 0) 1 else 0
                val add = x + r + 1
                if (add < w) cnt += src[o + add].toInt()
                val rem = x - r
                if (rem >= 0) cnt -= src[o + rem].toInt()
            }
        }
        for (x in 0 until w) {
            var cnt = 0
            for (y in 0..min(r, h - 1)) cnt += tmpA[y * w + x].toInt()
            for (y in 0 until h) {
                out[y * w + x] = if (cnt > 0) 1 else 0
                val add = y + r + 1
                if (add < h) cnt += tmpA[add * w + x].toInt()
                val rem = y - r
                if (rem >= 0) cnt -= tmpA[rem * w + x].toInt()
            }
        }
        return out
    }

    /** 3x3 average of a 0/1 mask as floats. */
    fun blur3(src: ByteArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        s += src[yy * w + xx].toInt()
                    }
                }
                out[y * w + x] = s / 9f
            }
        }
        return out
    }

    /** Box blur that only averages known pixels (known == 1). Unknown pixels keep their own value if nothing is known nearby. */
    fun boxBlurNorm(v: FloatArray, known: ByteArray, w: Int, h: Int, r: Int): FloatArray {
        val n = w * h
        val sv = FloatArray(n)
        val sw = FloatArray(n)
        for (y in 0 until h) {
            val o = y * w
            var a = 0f
            var b = 0f
            for (x in 0..min(r, w - 1)) {
                if (known[o + x].toInt() == 1) { a += v[o + x]; b += 1f }
            }
            for (x in 0 until w) {
                sv[o + x] = a
                sw[o + x] = b
                val add = x + r + 1
                if (add < w && known[o + add].toInt() == 1) { a += v[o + add]; b += 1f }
                val rem = x - r
                if (rem >= 0 && known[o + rem].toInt() == 1) { a -= v[o + rem]; b -= 1f }
            }
        }
        val out = FloatArray(n)
        for (x in 0 until w) {
            var a = 0f
            var b = 0f
            for (y in 0..min(r, h - 1)) { a += sv[y * w + x]; b += sw[y * w + x] }
            for (y in 0 until h) {
                val i = y * w + x
                out[i] = if (b > 0.5f) a / b else v[i]
                val add = y + r + 1
                if (add < h) { a += sv[add * w + x]; b += sw[add * w + x] }
                val rem = y - r
                if (rem >= 0) { a -= sv[rem * w + x]; b -= sw[rem * w + x] }
            }
        }
        return out
    }
}
