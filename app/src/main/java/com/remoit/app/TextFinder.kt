package com.remoit.app

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max

/** On-device text detection (Google ML Kit, model bundled in the app). Returns boxes as 0..1 of the picture. */
object TextFinder {
    suspend fun find(bmp: Bitmap): List<RectF> = suspendCancellableCoroutine { cont ->
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                val out = ArrayList<RectF>()
                for (block in t.textBlocks) {
                    for (line in block.lines) {
                        val r = line.boundingBox ?: continue
                        val pad = max(4f, r.height() * 0.14f)
                        out.add(
                            RectF(
                                ((r.left - pad) / bw).coerceIn(0f, 1f),
                                ((r.top - pad) / bh).coerceIn(0f, 1f),
                                ((r.right + pad) / bw).coerceIn(0f, 1f),
                                ((r.bottom + pad) / bh).coerceIn(0f, 1f)
                            )
                        )
                    }
                }
                try { rec.close() } catch (e: Exception) { }
                if (cont.isActive) cont.resume(out.take(40))
            }
            .addOnFailureListener {
                try { rec.close() } catch (e: Exception) { }
                if (cont.isActive) cont.resume(emptyList())
            }
    }
}
