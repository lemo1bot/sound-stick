package com.foriad.soundstick

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sin

/**
 * Renders a sticker + its sound into a short MP4 video ("Share with Sound").
 *
 * Phase 1 encodes the sticker bitmap to a silent MP4 with a MediaCodec
 * surface encoder (subtle pulse animation, ~3s, 512x512).
 * Phase 2 muxes the audio in: AAC/m4a tracks are copied directly with
 * MediaExtractor; WAV (effects / TTS) and other formats are decoded to PCM
 * and transcoded to AAC with MediaCodec. If the audio step fails, the
 * silent MP4 is returned so sharing still works.
 */
object VideoMaker {
    private const val SIZE = 512
    private const val FPS = 30
    private const val DURATION_MS = 3000L
    private const val VIDEO_BITRATE = 1_500_000
    private const val MAX_AUDIO_SECONDS = 4.0

    data class Result(val file: File, val hasAudio: Boolean)

    fun makeShareVideo(workDir: File, bitmap: Bitmap, audioFile: File?): Result {
        workDir.mkdirs()
        val silent = File(workDir, "video_only.mp4")
        encodeVideoOnly(bitmap, silent)
        if (audioFile == null || !audioFile.exists()) return Result(silent, false)
        val out = File(workDir, "share_with_sound.mp4")
        val ok = try {
            mergeAudio(silent, audioFile, out)
        } catch (e: Exception) {
            false
        }
        return if (ok && out.exists() && out.length() > 0) {
            Result(out, true)
        } else {
            Result(silent, false)
        }
    }

    // ---------------- Phase 1: bitmap -> silent MP4 ----------------

    private fun encodeVideoOnly(bitmap: Bitmap, outFile: File) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, SIZE, SIZE).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        fun drain(blocking: Boolean) {
            while (true) {
                val index = encoder.dequeueOutputBuffer(bufferInfo, if (blocking) 10_000 else 0)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        val buffer = encoder.getOutputBuffer(index)
                        if (buffer != null && bufferInfo.size > 0 && muxerStarted) {
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> return
                }
            }
        }

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val totalFrames = (DURATION_MS * FPS / 1000).toInt()
        val startNs = System.nanoTime()
        for (frame in 0 until totalFrames) {
            val canvas = surface.lockCanvas(null)
            try {
                canvas.drawColor(Color.BLACK)
                val pulse = 0.035f * sin(frame * 2.0 * Math.PI / FPS).toFloat()
                val inset = -SIZE * pulse
                canvas.drawBitmap(
                    bitmap, null,
                    RectF(inset, inset, SIZE - inset, SIZE - inset), paint
                )
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            drain(false)
            // Pace drawing in real time so encoder timestamps give ~3s.
            val targetNs = startNs + (frame + 1) * 1_000_000_000L / FPS
            val sleepMs = (targetNs - System.nanoTime()) / 1_000_000
            if (sleepMs > 1) Thread.sleep(sleepMs)
        }
        encoder.signalEndOfInputStream()
        drain(true)
        try {
            encoder.stop()
        } catch (e: Exception) {
            // ignore
        }
        encoder.release()
        if (muxerStarted) {
            try {
                muxer.stop()
            } catch (e: Exception) {
                // ignore
            }
        }
        muxer.release()
    }

    // ---------------- Phase 2: mux audio in ----------------

    private class PcmData(val sampleRate: Int, val channels: Int, val bytes: ByteArray)

    private fun mergeAudio(videoFile: File, audioFile: File, outFile: File): Boolean {
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(videoFile.absolutePath)
        var videoTrack = -1
        for (i in 0 until videoExtractor.trackCount) {
            if ((videoExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "")
                    .startsWith("video/")
            ) {
                videoTrack = i
                break
            }
        }
        if (videoTrack < 0) {
            videoExtractor.release()
            return false
        }
        videoExtractor.selectTrack(videoTrack)
        val videoFormat = videoExtractor.getTrackFormat(videoTrack)

        // Decide how the audio is handled.
        var aacCopyExtractor: MediaExtractor? = null
        var aacCopyTrack = -1
        var aacCopyFormat: MediaFormat? = null
        var pcm: PcmData? = null

        if (audioFile.extension.equals("wav", ignoreCase = true)) {
            pcm = readWavPcm(audioFile)
            if (pcm == null) {
                videoExtractor.release()
                return false
            }
        } else {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(audioFile.absolutePath)
                var found = -1
                for (i in 0 until ex.trackCount) {
                    if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "")
                            .startsWith("audio/")
                    ) {
                        found = i
                        break
                    }
                }
                if (found < 0) {
                    ex.release()
                    videoExtractor.release()
                    return false
                }
                val mime = ex.getTrackFormat(found).getString(MediaFormat.KEY_MIME)
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    aacCopyExtractor = ex
                    aacCopyTrack = found
                    aacCopyFormat = ex.getTrackFormat(found)
                    ex.selectTrack(found)
                } else {
                    // Decode compressed audio (mp3/ogg/...) to PCM, then AAC-encode.
                    pcm = decodeToPcm(ex, found)
                    ex.release()
                    if (pcm == null) {
                        videoExtractor.release()
                        return false
                    }
                }
            } catch (e: Exception) {
                ex.release()
                videoExtractor.release()
                return false
            }
        }

        // If transcoding, run the AAC encoder first and collect its output so
        // we know the audio format before the muxer starts.
        var encodedAudio: List<Pair<MediaCodec.BufferInfo, ByteArray>> = emptyList()
        var audioFormat: MediaFormat? = aacCopyFormat
        if (pcm != null) {
            val encoded = encodePcmToAac(pcm)
            if (encoded == null) {
                aacCopyExtractor?.release()
                videoExtractor.release()
                return false
            }
            audioFormat = encoded.first
            encodedAudio = encoded.second
        }
        if (audioFormat == null) {
            aacCopyExtractor?.release()
            videoExtractor.release()
            return false
        }

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outVideoTrack = muxer.addTrack(videoFormat)
        val outAudioTrack = muxer.addTrack(audioFormat)
        muxer.start()

        // Copy video samples.
        val buffer = ByteBuffer.allocate(512 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = videoExtractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = videoExtractor.sampleTime
            info.flags = if (videoExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            muxer.writeSampleData(outVideoTrack, buffer, info)
            videoExtractor.advance()
        }

        // Write audio samples.
        if (aacCopyExtractor != null) {
            var firstPts = -1L
            val maxPts = (DURATION_MS + 500) * 1000
            while (true) {
                val size = aacCopyExtractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = aacCopyExtractor.sampleTime
                if (firstPts < 0) firstPts = pts
                val rebased = pts - firstPts
                if (rebased > maxPts) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = maxOf(0, rebased)
                info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                muxer.writeSampleData(outAudioTrack, buffer, info)
                aacCopyExtractor.advance()
            }
            aacCopyExtractor.release()
        } else {
            for ((bi, bytes) in encodedAudio) {
                val buf = ByteBuffer.wrap(bytes)
                muxer.writeSampleData(outAudioTrack, buf, bi)
            }
        }

        try {
            muxer.stop()
        } catch (e: Exception) {
            // ignore
        }
        muxer.release()
        videoExtractor.release()
        return true
    }

    // ---------------- PCM helpers ----------------

    /** Parses a PCM WAV file (8/16-bit) into 16-bit PCM. */
    private fun readWavPcm(file: File): PcmData? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 44) return null
            fun u32le(off: Int): Long =
                (bytes[off].toLong() and 0xFF) or ((bytes[off + 1].toLong() and 0xFF) shl 8) or
                    ((bytes[off + 2].toLong() and 0xFF) shl 16) or ((bytes[off + 3].toLong() and 0xFF) shl 24)
            fun u16le(off: Int): Int =
                (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)

            var channels = 1
            var sampleRate = 22050
            var bits = 16
            var dataOff = -1
            var dataLen = 0
            var off = 12
            while (off + 8 <= bytes.size) {
                val id = String(bytes, off, 4, Charsets.US_ASCII)
                val size = u32le(off + 4).toInt()
                when (id) {
                    "fmt " -> {
                        if (u16le(off + 8) != 1) return null // not PCM
                        channels = u16le(off + 10)
                        sampleRate = u32le(off + 12).toInt()
                        bits = u16le(off + 22)
                    }
                    "data" -> {
                        dataOff = off + 8
                        dataLen = minOf(size, bytes.size - dataOff)
                        break
                    }
                }
                off += 8 + size + (size % 2)
            }
            if (dataOff < 0) return null
            val maxBytes = (sampleRate * channels * 2 * MAX_AUDIO_SECONDS).toInt()
            val pcm16: ByteArray = when (bits) {
                16 -> bytes.copyOfRange(dataOff, minOf(dataOff + dataLen, dataOff + maxBytes))
                8 -> {
                    val count = minOf(dataLen, maxBytes / 2)
                    val out = ByteArray(count * 2)
                    for (i in 0 until count) {
                        val v = ((bytes[dataOff + i].toInt() and 0xFF) - 128) shl 8
                        out[i * 2] = (v and 0xFF).toByte()
                        out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    out
                }
                else -> return null
            }
            PcmData(sampleRate, channels, pcm16)
        } catch (e: Exception) {
            null
        }
    }

    /** Decodes a compressed audio track to 16-bit PCM (capped). */
    private fun decodeToPcm(extractor: MediaExtractor, track: Int): PcmData? {
        extractor.selectTrack(track)
        val inputFormat = extractor.getTrackFormat(track)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()
        val out = ByteArrayOutputStream()
        val maxBytes = (sampleRate * channels * 2 * MAX_AUDIO_SECONDS).toInt()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone && out.size() < maxBytes) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inIndex, 0, size, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIndex >= 0 -> {
                        val outBuf = decoder.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            outBuf.get(chunk)
                            out.write(chunk)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            try {
                decoder.stop()
            } catch (e: Exception) {
                // ignore
            }
            decoder.release()
        }
        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) null else PcmData(sampleRate, channels, bytes)
    }

    /** Encodes 16-bit PCM to AAC; returns (format, collected samples). */
    private fun encodePcmToAac(pcm: PcmData): Pair<MediaFormat, List<Pair<MediaCodec.BufferInfo, ByteArray>>>? {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate, pcm.channels
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val samples = mutableListOf<Pair<MediaCodec.BufferInfo, ByteArray>>()
        var outputFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var inputOffset = 0
        var inputDone = false
        var outputDone = false
        var sampleIndex = 0L
        val bytesPerFrame = 2 * pcm.channels
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = encoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = encoder.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        val remaining = pcm.bytes.size - inputOffset
                        if (remaining <= 0) {
                            encoder.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val chunk = minOf(remaining, inBuf.remaining())
                            inBuf.put(pcm.bytes, inputOffset, chunk)
                            val frames = chunk / bytesPerFrame
                            val pts = sampleIndex * 1_000_000L / pcm.sampleRate
                            encoder.queueInputBuffer(inIndex, 0, chunk, pts, 0)
                            sampleIndex += frames
                            inputOffset += chunk
                        }
                    }
                }
                val outIndex = encoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = encoder.outputFormat
                    }
                    outIndex >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val data = ByteArray(info.size)
                            outBuf.get(data)
                            val copy = MediaCodec.BufferInfo().apply {
                                offset = 0
                                size = info.size
                                presentationTimeUs = info.presentationTimeUs
                                flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                            }
                            samples.add(copy to data)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            try {
                encoder.stop()
            } catch (e: Exception) {
                // ignore
            }
            encoder.release()
        }
        val fmt = outputFormat ?: return null
        if (samples.isEmpty()) return null
        return fmt to samples
    }
}
