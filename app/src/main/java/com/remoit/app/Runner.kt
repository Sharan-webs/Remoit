package com.remoit.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

object Runner {

    /** The AI model is used for images and for videos up to 3 minutes (AI on every frame would take too long). */
    private const val AI_VIDEO_MAX_MS = 180_000L

    fun run(
        ctx: Context, job: JobSpec,
        cb: (Float, String, Int, String) -> Unit, cancelled: () -> Boolean
    ): Project {
        var allow = true
        if (job.video) {
            val u = job.uri
            allow = try { u != null && VideoPipeline.probe(ctx, u).durationMs <= AI_VIDEO_MAX_MS } catch (e: Exception) { false }
        }
        Lama.prepare(ctx, allow, cb, cancelled)
        try {
            return runInner(ctx, job, cb, cancelled)
        } finally {
            Lama.enabled = false
        }
    }

    private fun runInner(
        ctx: Context, job: JobSpec,
        cb: (Float, String, Int, String) -> Unit, cancelled: () -> Boolean
    ): Project {
        val id = System.currentTimeMillis().toString()
        val dir = File(ctx.filesDir, "results")
        val tdir = File(ctx.filesDir, "thumbs")
        dir.mkdirs()
        tdir.mkdirs()
        val project: Project = if (job.video) {
            val uri = job.uri ?: throw IllegalArgumentException("The video is missing.")
            val out = File(dir, "remoit_$id.mp4")
            try {
                val r = VideoPipeline.run(ctx, uri, job.spec, out.absolutePath, cb, cancelled)
                val pr = VideoPipeline.probe(ctx, Uri.fromFile(out))
                val thumb = videoThumb(out, File(tdir, "t_$id.jpg"), r.durationMs)
                Project(id, System.currentTimeMillis(), "video", out.absolutePath, thumb, pr.dispW, pr.dispH, r.durationMs, r.note)
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
        } else {
            val path = job.imagePath ?: throw IllegalArgumentException("The image is missing.")
            val out = File(dir, "remoit_$id" + (if (job.asJpeg) ".jpg" else ".png"))
            try {
                val dims = ImageProcessor.run(ctx, path, job.spec, out, job.asJpeg, cb)
                val thumb = imageThumb(out, File(tdir, "t_$id.jpg"))
                Project(id, System.currentTimeMillis(), "image", out.absolutePath, thumb, dims[0], dims[1], 0L, "")
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
        }
        History.add(project)
        Credits.consume()
        return project
    }

    private fun save(bm: Bitmap, f: File) {
        FileOutputStream(f).use { bm.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    private fun scaled(bm: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / max(bm.width, bm.height)
        if (s >= 1f) return bm
        return Bitmap.createScaledBitmap(bm, max(1, (bm.width * s).toInt()), max(1, (bm.height * s).toInt()), true)
    }

    private fun videoThumb(video: File, out: File, durMs: Long): String {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(video.absolutePath)
            val t = min(1_000_000L, durMs * 500L)
            val bm = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0)
            r.release()
            if (bm == null) "" else { save(scaled(bm, 480), out); out.absolutePath }
        } catch (e: Exception) { "" }
    }

    private fun imageThumb(img: File, out: File): String {
        return try {
            val bm = ImageIO.loadPreview(img.absolutePath, 480)
            save(scaled(bm, 480), out)
            out.absolutePath
        } catch (e: Throwable) { "" }
    }
}
