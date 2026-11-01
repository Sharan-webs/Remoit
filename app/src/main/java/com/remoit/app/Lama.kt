package com.remoit.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Free AI inpainting: LaMa (big-lama, Apache-2.0), ONNX export by Carve, run on the phone with ONNX Runtime (MIT).
 * The model file (about 208 MB) is downloaded ONCE on first use and kept in the app's private storage.
 * Nothing of the user's photos or videos is ever uploaded. If the model is missing or anything fails,
 * remoit silently falls back to the built-in texture fill (PatchFill).
 *
 * Model I/O (fixed size): image float32 [1,3,512,512] RGB 0..1, mask float32 [1,1,512,512] (1 = fill),
 * output float32 [1,3,512,512] RGB 0..255.
 */
object Lama {
    private const val MODEL_URL = "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"
    private const val EXPECTED_BYTES = 208_000_000L
    private const val MIN_BYTES = 150_000_000L
    private const val S = 512

    /** True only while a job that may use the AI is running. */
    @Volatile
    var enabled = false

    @Volatile
    var lastError = ""

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var imageName = "image"
    private var maskName = "mask"

    fun modelFile(ctx: Context): File = File(File(ctx.filesDir, "models"), "lama_fp32.onnx")

    /** Gets the model ready (download once + load). Sets [enabled]. Never throws except for cancel. */
    fun prepare(ctx: Context, allow: Boolean, cb: (Float, String, Int, String) -> Unit, cancelled: () -> Boolean) {
        enabled = false
        if (!allow) return
        try {
            if (session == null) {
                if (!download(ctx, cb, cancelled)) return
                cb(0.01f, "Loading AI model", 0, "")
                load(ctx)
            }
            enabled = session != null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            enabled = false
        }
    }

    private fun load(ctx: Context) {
        val f = modelFile(ctx)
        try {
            val e = OrtEnvironment.getEnvironment()
            val o = OrtSession.SessionOptions()
            o.setIntraOpNumThreads(max(2, min(4, Runtime.getRuntime().availableProcessors())))
            val s = e.createSession(f.absolutePath, o)
            val names = s.inputNames.toList()
            maskName = names.firstOrNull { it.contains("mask", ignoreCase = true) } ?: names.last()
            imageName = names.firstOrNull { it != maskName } ?: names.first()
            env = e
            session = s
        } catch (t: Throwable) {
            // a broken download must not stay on the phone
            f.delete()
            session = null
            throw t
        }
    }

    private fun download(ctx: Context, cb: (Float, String, Int, String) -> Unit, cancelled: () -> Boolean): Boolean {
        val f = modelFile(ctx)
        if (f.exists() && f.length() >= MIN_BYTES) return true
        f.parentFile?.mkdirs()
        val part = File(f.parentFile, "lama_fp32.onnx.part")
        var conn: HttpURLConnection? = null
        try {
            part.delete()
            conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "remoit-android")
            if (conn.responseCode !in 200..299) return false
            val len = conn.contentLengthLong
            val total = if (len > 0) len else EXPECTED_BYTES
            var done = 0L
            var lastCb = 0L
            conn.inputStream.use { inp ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw CancellationException("cancelled")
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastCb > 400) {
                            lastCb = now
                            val p = (done.toFloat() / total).coerceIn(0f, 1f)
                            cb(p, "Downloading AI model (one time)", 0, (done / 1_000_000L).toString() + " MB of " + (total / 1_000_000L) + " MB")
                        }
                    }
                }
            }
            if (part.length() < MIN_BYTES) { part.delete(); return false }
            if (f.exists()) f.delete()
            return part.renameTo(f)
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (t: Throwable) {
            part.delete()
            lastError = t.message ?: t.javaClass.simpleName
            return false
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    /** Mirror index so any integer maps inside 0 until n. */
    private fun refl(i: Int, n: Int): Int {
        if (n <= 1) return 0
        val p = 2 * (n - 1)
        val m = ((i % p) + p) % p
        return if (m < n) m else p - m
    }

    /**
     * Fills the hole pixels of one ROI with the AI. [ch] = input ROI (luma, cb, cr as in the rest of the app),
     * [hole] = 1 where to fill, [out] = array that receives the new colours for the hole pixels only.
     * The ROI is placed at 1:1 scale in the middle of a 512x512 canvas (mirrored around it) so no detail is lost;
     * only when the ROI is bigger than 512 px it is scaled down to fit.
     */
    @Synchronized
    fun fill(ch: Array<FloatArray>, hole: ByteArray, w: Int, h: Int, out: Array<FloatArray>): Boolean {
        val s = session ?: return false
        val e = env ?: return false
        var t1: OnnxTensor? = null
        var t2: OnnxTensor? = null
        var res: OrtSession.Result? = null
        try {
            val n = w * h
            val rr = FloatArray(n)
            val gg = FloatArray(n)
            val bb = FloatArray(n)
            for (i in 0 until n) {
                val y = ch[0][i]
                val cb = ch[1][i] - 128f
                val cr = ch[2][i] - 128f
                rr[i] = ((y + 1.402f * cr) / 255f).coerceIn(0f, 1f)
                gg[i] = ((y - 0.344136f * cb - 0.714136f * cr) / 255f).coerceIn(0f, 1f)
                bb[i] = ((y + 1.772f * cb) / 255f).coerceIn(0f, 1f)
            }

            val scale = if (max(w, h) > S) S.toFloat() / max(w, h) else 1f
            val sw = max(1, (w * scale).roundToInt())
            val sh = max(1, (h * scale).roundToInt())
            val ox = (S - sw) / 2
            val oy = (S - sh) / 2

            val img = FloatArray(3 * S * S)
            val msk = FloatArray(S * S)
            val plane = S * S
            for (Y in 0 until S) {
                val v = (Y - oy + 0.5f) / scale - 0.5f
                val y0 = floor(v).toInt()
                val ty = v - y0
                val ya = refl(y0, h)
                val yb = refl(y0 + 1, h)
                val yn = refl(floor(v + 0.5f).toInt(), h)
                for (X in 0 until S) {
                    val u = (X - ox + 0.5f) / scale - 0.5f
                    val x0 = floor(u).toInt()
                    val tx = u - x0
                    val xa = refl(x0, w)
                    val xb = refl(x0 + 1, w)
                    val xn = refl(floor(u + 0.5f).toInt(), w)
                    val p = Y * S + X
                    val i00 = ya * w + xa
                    val i01 = ya * w + xb
                    val i10 = yb * w + xa
                    val i11 = yb * w + xb
                    val w00 = (1 - tx) * (1 - ty)
                    val w01 = tx * (1 - ty)
                    val w10 = (1 - tx) * ty
                    val w11 = tx * ty
                    img[p] = rr[i00] * w00 + rr[i01] * w01 + rr[i10] * w10 + rr[i11] * w11
                    img[plane + p] = gg[i00] * w00 + gg[i01] * w01 + gg[i10] * w10 + gg[i11] * w11
                    img[2 * plane + p] = bb[i00] * w00 + bb[i01] * w01 + bb[i10] * w10 + bb[i11] * w11
                    msk[p] = if (hole[yn * w + xn].toInt() == 1) 1f else 0f
                }
            }

            val fbImg = ByteBuffer.allocateDirect(img.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fbImg.put(img)
            fbImg.rewind()
            val fbMsk = ByteBuffer.allocateDirect(msk.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fbMsk.put(msk)
            fbMsk.rewind()
            t1 = OnnxTensor.createTensor(e, fbImg, longArrayOf(1, 3, S.toLong(), S.toLong()))
            t2 = OnnxTensor.createTensor(e, fbMsk, longArrayOf(1, 1, S.toLong(), S.toLong()))
            res = s.run(mapOf(imageName to t1, maskName to t2))
            val ot = res.get(0) as OnnxTensor
            val fb = ot.floatBuffer
            val o = FloatArray(3 * plane)
            fb.rewind()
            fb.get(o)

            var mx = 0f
            var q = 0
            while (q < o.size) { if (o[q] > mx) mx = o[q]; q += 7 }
            val k = if (mx <= 1.5f) 255f else 1f

            for (y in 0 until h) {
                val cy = ((y + 0.5f) * scale - 0.5f + oy).coerceIn(0f, (S - 1).toFloat())
                val y0 = cy.toInt()
                val y1 = min(y0 + 1, S - 1)
                val ty = cy - y0
                for (x in 0 until w) {
                    val i = y * w + x
                    if (hole[i].toInt() == 0) continue
                    val cx = ((x + 0.5f) * scale - 0.5f + ox).coerceIn(0f, (S - 1).toFloat())
                    val x0 = cx.toInt()
                    val x1 = min(x0 + 1, S - 1)
                    val tx = cx - x0
                    val a = (1 - tx) * (1 - ty)
                    val b = tx * (1 - ty)
                    val c = (1 - tx) * ty
                    val d = tx * ty
                    val r = (o[y0 * S + x0] * a + o[y0 * S + x1] * b + o[y1 * S + x0] * c + o[y1 * S + x1] * d) * k
                    val g = (o[plane + y0 * S + x0] * a + o[plane + y0 * S + x1] * b + o[plane + y1 * S + x0] * c + o[plane + y1 * S + x1] * d) * k
                    val bl = (o[2 * plane + y0 * S + x0] * a + o[2 * plane + y0 * S + x1] * b + o[2 * plane + y1 * S + x0] * c + o[2 * plane + y1 * S + x1] * d) * k
                    out[0][i] = (0.299f * r + 0.587f * g + 0.114f * bl).coerceIn(0f, 255f)
                    out[1][i] = (128f - 0.168736f * r - 0.331264f * g + 0.5f * bl).coerceIn(0f, 255f)
                    out[2][i] = (128f + 0.5f * r - 0.418688f * g - 0.081312f * bl).coerceIn(0f, 255f)
                }
            }
            return true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            return false
        } finally {
            try { res?.close() } catch (x: Throwable) { }
            try { t1?.close() } catch (x: Throwable) { }
            try { t2?.close() } catch (x: Throwable) { }
        }
    }
}
