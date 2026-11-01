package com.remoit.app

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

class VideoProbe(val w: Int, val h: Int, val rotation: Int, val durationMs: Long, val bitrate: Long, val fps: Float) {
    val dispW: Int get() = if (rotation == 90 || rotation == 270) h else w
    val dispH: Int get() = if (rotation == 90 || rotation == 270) w else h
}

class VideoResult(val w: Int, val h: Int, val durationMs: Long, val note: String)

object VideoPipeline {
    /** 2 hours 30 minutes */
    const val MAX_MS = 9_000_000L

    fun probe(ctx: Context, uri: Uri): VideoProbe {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (w == null || h == null || w <= 0 || h <= 0) throw IllegalArgumentException("This file has no readable video.")
            val rot = (((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) % 360) + 360) % 360
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val fps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 0f
            return VideoProbe(w, h, rot, dur, br, fps)
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    fun run(
        ctx: Context, uri: Uri, spec: EditSpec, outPath: String,
        progress: (Float, String, Int, String) -> Unit, cancelled: () -> Boolean
    ): VideoResult = Session(ctx, uri, spec, outPath, progress, cancelled).run()

    private class Session(
        val ctx: Context, val uri: Uri, val spec: EditSpec, val outPath: String,
        val progress: (Float, String, Int, String) -> Unit, val cancelled: () -> Boolean
    ) {
        private var pfdV: ParcelFileDescriptor? = null
        private var pfdA: ParcelFileDescriptor? = null
        private val ex = MediaExtractor()
        private val exA = MediaExtractor()
        private var dec: MediaCodec? = null
        private var enc: MediaCodec? = null
        private var mux: MediaMuxer? = null

        private var fw = 0
        private var fh = 0
        private var rot = 0
        private var mime = ""
        private var vf: MediaFormat? = null
        private var probeInfo: VideoProbe? = null
        private var items: List<MaskItem> = emptyList()

        private var videoTrack = -1
        private var audioTrack = -1
        private var audioFormat: MediaFormat? = null
        private var audioDone = true
        private var muxStarted = false
        private var encDone = false
        private var frameCounter = 0L
        private var note = ""
        private val binfo = MediaCodec.BufferInfo()
        private val ainfo = MediaCodec.BufferInfo()
        private var audioBuf: ByteBuffer = ByteBuffer.allocate(0)
        private var rowBuf = ByteArray(0)
        private var rowBuf2 = ByteArray(0)

        fun run(): VideoResult {
            try {
                return runInner()
            } finally {
                try { dec?.stop() } catch (e: Exception) { }
                try { dec?.release() } catch (e: Exception) { }
                try { enc?.stop() } catch (e: Exception) { }
                try { enc?.release() } catch (e: Exception) { }
                try { if (muxStarted) mux?.stop() } catch (e: Exception) { }
                try { mux?.release() } catch (e: Exception) { }
                try { ex.release() } catch (e: Exception) { }
                try { exA.release() } catch (e: Exception) { }
                try { pfdV?.close() } catch (e: Exception) { }
                try { pfdA?.close() } catch (e: Exception) { }
            }
        }

        private fun runInner(): VideoResult {
            progress(0f, "Opening video", 0, "")
            val p = VideoPipeline.probe(ctx, uri)
            probeInfo = p
            if (p.durationMs > MAX_MS + 2000) throw IllegalArgumentException("This video is longer than 2 hours 30 minutes.")
            rot = p.rotation

            pfdV = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalArgumentException("Could not open the video.")
            ex.setDataSource(pfdV!!.fileDescriptor)
            var vIdx = -1
            var aIdx = -1
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (vIdx < 0 && m.startsWith("video/")) vIdx = i
                if (aIdx < 0 && m.startsWith("audio/")) aIdx = i
            }
            if (vIdx < 0) throw IllegalArgumentException("This file has no video track.")
            ex.selectTrack(vIdx)
            val f = ex.getTrackFormat(vIdx)
            vf = f
            mime = f.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            fw = f.getInteger(MediaFormat.KEY_WIDTH)
            fh = f.getInteger(MediaFormat.KEY_HEIGHT)

            if (Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                val t = f.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                if (t == MediaFormat.COLOR_TRANSFER_ST2084 || t == MediaFormat.COLOR_TRANSFER_HLG) {
                    throw IllegalArgumentException("HDR / 10-bit videos are not supported yet, because they would lose quality. Use a normal (SDR) video.")
                }
            }

            if (aIdx >= 0) {
                try {
                    pfdA = ctx.contentResolver.openFileDescriptor(uri, "r")
                    exA.setDataSource(pfdA!!.fileDescriptor)
                    exA.selectTrack(aIdx)
                    audioFormat = exA.getTrackFormat(aIdx)
                    val mx = if (audioFormat!!.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) audioFormat!!.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                    audioBuf = ByteBuffer.allocate(max(mx, 1 shl 20))
                    audioDone = false
                } catch (e: Exception) {
                    audioFormat = null
                    audioDone = true
                    note = "Audio could not be copied."
                }
            }

            items = MaskBuilder.build(spec, fw, fh, rot)
            if (items.isEmpty()) throw IllegalArgumentException("Nothing is marked. Create a box or draw with the pen first.")

            val d = MediaCodec.createDecoderByType(mime)
            dec = d
            // ask the decoder for a format we can read (flexible YUV 4:2:0)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            d.configure(f, null, null, 0)
            d.start()

            val durUs = max(1L, p.durationMs * 1000L)
            val t0 = System.currentTimeMillis()
            var lastReport = 0L
            var inputDone = false
            var decDone = false
            var lastPts = 0L
            val info = MediaCodec.BufferInfo()

            while (!decDone) {
                if (cancelled()) throw CancellationException("cancelled")
                if (!inputDone) {
                    val ii = d.dequeueInputBuffer(2000)
                    if (ii >= 0) {
                        val ib = d.getInputBuffer(ii)
                        val sz = if (ib != null) ex.readSampleData(ib, 0) else -1
                        if (sz < 0) {
                            d.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            d.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = d.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (info.size > 0) {
                        val img = d.getOutputImage(oi) ?: throw IllegalStateException("This phone's decoder does not give readable frames.")
                        if (enc == null) createEncoder(d.getOutputFormat(oi))
                        submitFrame(img, info.presentationTimeUs)
                        img.close()
                        lastPts = info.presentationTimeUs
                    }
                    d.releaseOutputBuffer(oi, false)
                    if (eos) decDone = true

                    val now = System.currentTimeMillis()
                    if (now - lastReport > 500) {
                        lastReport = now
                        val frac = (lastPts.toFloat() / durUs).coerceIn(0f, 1f)
                        val elapsed = (now - t0) / 1000f
                        val eta = if (frac > 0.003f) (elapsed / frac * (1f - frac)).toInt() else 0
                        progress(frac, "Removing text", eta, "Frame $frameCounter  •  original quality kept")
                    }
                }
            }

            // finish the encoder
            val e = enc ?: throw IllegalStateException("The video had no frames.")
            while (true) {
                if (cancelled()) throw CancellationException("cancelled")
                val ei = e.dequeueInputBuffer(10_000)
                if (ei >= 0) {
                    e.queueInputBuffer(ei, 0, 0, lastPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drainEncoder(0)
            }
            while (!encDone) {
                if (cancelled()) throw CancellationException("cancelled")
                drainEncoder(10_000)
            }
            progress(0.995f, "Saving video", 0, "")
            pumpAudio(Long.MAX_VALUE)
            return VideoResult(fw, fh, p.durationMs, note)
        }

        private fun hasEncoder(m: String): Boolean {
            return try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
                    ci.isEncoder && ci.supportedTypes.any { it.equals(m, true) }
                }
            } catch (e: Exception) { false }
        }

        private fun fpsOf(f: MediaFormat, p: VideoProbe): Int {
            if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try { return max(1, Math.round(f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat())) } catch (e: Exception) { }
                try { return max(1, Math.round(f.getFloat(MediaFormat.KEY_FRAME_RATE))) } catch (e: Exception) { }
            }
            return if (p.fps > 1f) Math.round(p.fps) else 30
        }

        private fun createEncoder(decFmt: MediaFormat) {
            val f = vf ?: throw IllegalStateException("no format")
            val p = probeInfo ?: throw IllegalStateException("no probe")
            val useHevc = mime.equals("video/hevc", true) && hasEncoder("video/hevc")
            val encMime = if (useHevc) "video/hevc" else "video/avc"

            var srcBr = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) f.getInteger(MediaFormat.KEY_BIT_RATE).toLong() else 0L
            if (srcBr <= 0L) srcBr = max(0L, p.bitrate - 128_000L)
            if (srcBr <= 0L) srcBr = fw.toLong() * fh * 30L / 8L   // about 3.75 bits per pixel-second as a last guess
            // re-encoding costs a little quality, so spend a bit more bitrate than the original had
            val mult = if (encMime.equals(mime, true)) 1.35 else 1.8
            val bitrate = (srcBr * mult).toLong().coerceIn(2_000_000L, 120_000_000L).toInt()
            val fps = fpsOf(f, p)

            fun format(withProfile: Boolean): MediaFormat {
                val fmt = MediaFormat.createVideoFormat(encMime, fw, fh)
                fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                if (Build.VERSION.SDK_INT >= 24) {
                    for (k in listOf(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER)) {
                        if (decFmt.containsKey(k)) fmt.setInteger(k, decFmt.getInteger(k))
                        else if (f.containsKey(k)) fmt.setInteger(k, f.getInteger(k))
                    }
                }
                if (withProfile && encMime == "video/avc") {
                    fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                }
                return fmt
            }

            var made: MediaCodec? = null
            var lastErr: Exception? = null
            for (withProfile in listOf(true, false)) {
                try {
                    val c = MediaCodec.createEncoderByType(encMime)
                    try {
                        c.configure(format(withProfile), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        c.start()
                        made = c
                        break
                    } catch (e: Exception) {
                        lastErr = e
                        try { c.release() } catch (x: Exception) { }
                    }
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            enc = made ?: throw IllegalStateException("This phone's video encoder refused the video (" + (lastErr?.message ?: "unknown") + ").")
            val m = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            m.setOrientationHint(rot)
            mux = m
        }

        private fun startMuxer(videoFormat: MediaFormat) {
            val m = mux ?: return
            videoTrack = m.addTrack(videoFormat)
            val af = audioFormat
            if (af != null) {
                try {
                    audioTrack = m.addTrack(af)
                } catch (e: Exception) {
                    audioTrack = -1
                    audioDone = true
                    note = "The audio format cannot be stored in MP4, so the video was saved without sound."
                }
            }
            m.start()
            muxStarted = true
        }

        private fun drainEncoder(timeoutUs: Long) {
            val e = enc ?: return
            while (true) {
                val oi = e.dequeueOutputBuffer(binfo, timeoutUs)
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) return
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    startMuxer(e.outputFormat)
                    continue
                }
                if (oi >= 0) {
                    val buf = e.getOutputBuffer(oi)
                    if ((binfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) binfo.size = 0
                    if (binfo.size > 0 && buf != null && muxStarted) {
                        buf.position(binfo.offset)
                        buf.limit(binfo.offset + binfo.size)
                        mux!!.writeSampleData(videoTrack, buf, binfo)
                        pumpAudio(binfo.presentationTimeUs + 700_000L)
                    }
                    e.releaseOutputBuffer(oi, false)
                    if ((binfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encDone = true
                        return
                    }
                }
            }
        }

        /** Copies the original audio untouched (no re-encoding, so no quality loss). */
        private fun pumpAudio(limitUs: Long) {
            if (audioTrack < 0 || audioDone || !muxStarted) return
            val m = mux ?: return
            while (true) {
                val t = exA.sampleTime
                if (t < 0) { audioDone = true; return }
                if (t > limitUs) return
                audioBuf.clear()
                val size = exA.readSampleData(audioBuf, 0)
                if (size < 0) { audioDone = true; return }
                ainfo.set(0, size, t, if ((exA.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(audioTrack, audioBuf, ainfo)
                exA.advance()
            }
        }

        private fun submitFrame(src: Image, pts: Long) {
            val e = enc ?: return
            while (true) {
                if (cancelled()) throw CancellationException("cancelled")
                drainEncoder(0)
                val ei = e.dequeueInputBuffer(10_000)
                if (ei < 0) continue
                val dst = e.getInputImage(ei) ?: throw IllegalStateException("This phone's encoder does not accept readable frames.")
                renderFrame(src, dst, pts)
                e.queueInputBuffer(ei, 0, fw * fh * 3 / 2, pts, 0)
                return
            }
        }

        private fun renderFrame(src: Image, dst: Image, pts: Long) {
            copyFrame(src, dst)
            for (item in items) {
                if (pts < item.startUs || pts > item.endUs) continue
                val p = item.proc
                Yuv.readRoi(src, item, p.ch)
                if (p.process(frameCounter)) Yuv.writeRoi(dst, item, p)
            }
            frameCounter++
        }

        private fun copyFrame(src: Image, dst: Image) {
            val sp = src.planes
            val dp = dst.planes
            val sc = src.cropRect
            val dc = dst.cropRect
            for (i in 0..2) {
                val sh = if (i == 0) 0 else 1
                val pw = if (i == 0) fw else (fw + 1) / 2
                val ph = if (i == 0) fh else (fh + 1) / 2
                copyPlane(sp[i], dp[i], pw, ph, sc.left shr sh, sc.top shr sh, dc.left shr sh, dc.top shr sh)
            }
        }

        private fun copyPlane(sp: Image.Plane, dp: Image.Plane, pw: Int, ph: Int, sx: Int, sy: Int, dx: Int, dy: Int) {
            val srs = sp.rowStride
            val drs = dp.rowStride
            val sps = sp.pixelStride
            val dps = dp.pixelStride
            val s = sp.buffer.duplicate()
            val d = dp.buffer.duplicate()
            if (sps == 1 && dps == 1) {
                if (rowBuf.size < pw) rowBuf = ByteArray(pw)
                for (y in 0 until ph) {
                    s.position((sy + y) * srs + sx)
                    s.get(rowBuf, 0, pw)
                    d.position((dy + y) * drs + dx)
                    d.put(rowBuf, 0, pw)
                }
            } else {
                // interleaved chroma: read the destination row, patch only our own samples, write it back,
                // so the neighbouring interleaved plane is never disturbed (works for NV12 and NV21 alike)
                val slen = (pw - 1) * sps + 1
                val dlen = (pw - 1) * dps + 1
                if (rowBuf.size < slen) rowBuf = ByteArray(slen)
                if (rowBuf2.size < dlen) rowBuf2 = ByteArray(dlen)
                for (y in 0 until ph) {
                    s.position((sy + y) * srs + sx * sps)
                    s.get(rowBuf, 0, slen)
                    val dpos = (dy + y) * drs + dx * dps
                    d.position(dpos)
                    d.get(rowBuf2, 0, dlen)
                    for (x in 0 until pw) rowBuf2[x * dps] = rowBuf[x * sps]
                    d.position(dpos)
                    d.put(rowBuf2, 0, dlen)
                }
            }
        }
    }
}

/** Reads and writes small regions of YUV 4:2:0 images, without converting the rest of the frame. */
object Yuv {
    fun readRoi(img: Image, item: MaskItem, ch: Array<FloatArray>) {
        val pl = img.planes
        val crop = img.cropRect
        val yb = pl[0].buffer
        val ub = pl[1].buffer
        val vb = pl[2].buffer
        val yrs = pl[0].rowStride
        val yps = pl[0].pixelStride
        val urs = pl[1].rowStride
        val ups = pl[1].pixelStride
        val vrs = pl[2].rowStride
        val vps = pl[2].pixelStride
        val w = item.w
        for (y in 0 until item.h) {
            val fy = item.y0 + y + crop.top
            val yo = fy * yrs
            val uo = (fy shr 1) * urs
            val vo = (fy shr 1) * vrs
            var i = y * w
            for (x in 0 until w) {
                val fx = item.x0 + x + crop.left
                ch[0][i] = (yb.get(yo + fx * yps).toInt() and 0xFF).toFloat()
                ch[1][i] = (ub.get(uo + (fx shr 1) * ups).toInt() and 0xFF).toFloat()
                ch[2][i] = (vb.get(vo + (fx shr 1) * vps).toInt() and 0xFF).toFloat()
                i++
            }
        }
    }

    fun writeRoi(img: Image, item: MaskItem, p: ItemProcessor) {
        val pl = img.planes
        val crop = img.cropRect
        val yb = pl[0].buffer
        val ub = pl[1].buffer
        val vb = pl[2].buffer
        val yrs = pl[0].rowStride
        val yps = pl[0].pixelStride
        val urs = pl[1].rowStride
        val ups = pl[1].pixelStride
        val vrs = pl[2].rowStride
        val vps = pl[2].pixelStride
        val w = item.w
        val h = item.h
        val bw = (w + 1) / 2
        java.util.Arrays.fill(p.blockMark, 0.toByte())
        val idx = p.holeIdx
        for (k in idx.indices) {
            val i = idx[k]
            val x = i % w
            val y = i / w
            val v = Math.round(p.res[0][i]).coerceIn(0, 255)
            yb.put((item.y0 + y + crop.top) * yrs + (item.x0 + x + crop.left) * yps, v.toByte())
            p.blockMark[(y shr 1) * bw + (x shr 1)] = 1
        }
        val bh = (h + 1) / 2
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                if (p.blockMark[by * bw + bx].toInt() == 0) continue
                var su = 0f
                var sv = 0f
                var c = 0
                for (dy in 0..1) {
                    val yy = by * 2 + dy
                    if (yy >= h) continue
                    for (dx in 0..1) {
                        val xx = bx * 2 + dx
                        if (xx >= w) continue
                        su += p.res[1][yy * w + xx]
                        sv += p.res[2][yy * w + xx]
                        c++
                    }
                }
                if (c == 0) continue
                val fy = ((item.y0 + by * 2 + crop.top) shr 1)
                val fx = ((item.x0 + bx * 2 + crop.left) shr 1)
                ub.put(fy * urs + fx * ups, Math.round(su / c).coerceIn(0, 255).toByte())
                vb.put(fy * vrs + fx * vps, Math.round(sv / c).coerceIn(0, 255).toByte())
            }
        }
    }
}
