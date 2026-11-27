package com.foriad.soundstick

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pure-Kotlin animated WebP muxer/demuxer (no native libraries).
 *
 * WRITER: each frame bitmap is encoded on its own with Bitmap.compress
 * (WEBP_LOSSY). A single-frame WebP file is a RIFF container holding one
 * image chunk ("VP8 " for lossy, "VP8L" for lossless, optionally preceded
 * by an "ALPH" alpha chunk). We lift those chunks out and reassemble them
 * into an extended WebP file:
 *
 *   RIFF/WEBP
 *   VP8X  (canvas 512x512, animation flag, alpha flag if any frame has ALPH)
 *   ANIM  (background colour, loop count 0 = forever)
 *   ANMF  (frame header + the frame's image chunks)  x N
 *
 * Per the WebP Extended File Format spec, every chunk is padded to an even
 * size, ANMF frame coordinates are stored divided by 2 (our frames are
 * full-canvas at 0,0 so they are 0), width/height are stored minus one,
 * and the frame duration is in milliseconds (>= 8 ms for WhatsApp).
 *
 * READER: parses ANMF chunks back out and wraps each frame's image chunks
 * in a minimal standalone WebP file so BitmapFactory can decode it. Used
 * to animate stickers in the pack grid.
 */
object AnimatedWebPWriter {

    private class ImageChunks(
        val imageChunkId: String,       // "VP8 " or "VP8L"
        val imageData: ByteArray,
        val alphaData: ByteArray?       // "ALPH" payload, if present
    )

    private fun webpFormat(): Bitmap.CompressFormat =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }

    /** Encodes one bitmap as a single-frame WebP and extracts its chunks. */
    private fun encodeFrameChunks(bitmap: Bitmap, quality: Int): ImageChunks? {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(webpFormat(), quality, out)) return null
        return extractImageChunks(out.toByteArray())
    }

    /** Pulls the VP8 /VP8L (+ optional ALPH) chunks out of a WebP file. */
    private fun extractImageChunks(webp: ByteArray): ImageChunks? {
        if (webp.size < 20) return null
        if (!fourCcEquals(webp, 0, "RIFF") || !fourCcEquals(webp, 8, "WEBP")) return null
        var offset = 12
        var imageId: String? = null
        var imageData: ByteArray? = null
        var alphaData: ByteArray? = null
        while (offset + 8 <= webp.size) {
            val id = fourCc(webp, offset)
            val size = u32le(webp, offset + 4).toInt()
            if (size < 0 || offset + 8 + size > webp.size) break
            val payload = webp.copyOfRange(offset + 8, offset + 8 + size)
            when (id) {
                "VP8 ", "VP8L" -> {
                    imageId = id
                    imageData = payload
                }
                "ALPH" -> alphaData = payload
            }
            offset += 8 + size + (size and 1)
        }
        val id = imageId ?: return null
        val data = imageData ?: return null
        return ImageChunks(id, data, alphaData)
    }

    /**
     * Builds an animated WebP from [frames] (all the same size), each shown
     * for [frameDurationMs]. Returns null if any frame fails to encode.
     */
    fun encode(frames: List<Bitmap>, frameDurationMs: Int, quality: Int): ByteArray? {
        if (frames.isEmpty()) return null
        val width = frames.first().width
        val height = frames.first().height
        val encoded = ArrayList<ImageChunks>(frames.size)
        var anyAlpha = false
        for (frame in frames) {
            val chunks = encodeFrameChunks(frame, quality) ?: return null
            if (chunks.alphaData != null) anyAlpha = true
            encoded.add(chunks)
        }

        val body = ByteArrayOutputStream()
        body.writeAscii("WEBP")

        // ---- VP8X: flags, reserved, canvas size - 1 ----
        val vp8x = ByteArrayOutputStream()
        // bit 4 (0x10) = alpha present, bit 1 (0x02) = animation present
        vp8x.write(if (anyAlpha) 0x12 else 0x02)
        vp8x.write(0); vp8x.write(0); vp8x.write(0) // reserved
        writeU24le(vp8x, width - 1)
        writeU24le(vp8x, height - 1)
        writeChunk(body, "VP8X", vp8x.toByteArray())

        // ---- ANIM: background (BGRA), loop count (0 = forever) ----
        val anim = ByteArrayOutputStream()
        anim.write(0); anim.write(0); anim.write(0); anim.write(0)
        anim.write(0); anim.write(0) // loop forever
        writeChunk(body, "ANIM", anim.toByteArray())

        // ---- ANMF frames ----
        val duration = frameDurationMs.coerceIn(8, 0xFFFFFF)
        for (chunks in encoded) {
            val anmf = ByteArrayOutputStream()
            writeU24le(anmf, 0) // frame X / 2 (full canvas at 0,0)
            writeU24le(anmf, 0) // frame Y / 2
            writeU24le(anmf, width - 1)
            writeU24le(anmf, height - 1)
            writeU24le(anmf, duration)
            anmf.write(0) // flags: no disposal, alpha-blend (frames are opaque)
            if (chunks.alphaData != null) writeChunk(anmf, "ALPH", chunks.alphaData)
            writeChunk(anmf, chunks.imageChunkId, chunks.imageData)
            writeChunk(body, "ANMF", anmf.toByteArray())
        }

        val bodyBytes = body.toByteArray()
        val riff = ByteArrayOutputStream()
        riff.writeAscii("RIFF")
        writeU32le(riff, bodyBytes.size.toLong()) // RIFF size = file size - 8 = "WEBP" + chunks
        riff.write(bodyBytes)
        if (riff.size() % 2 == 1) riff.write(0)
        return riff.toByteArray()
    }

    // ---------------- RIFF helpers ----------------

    private fun writeChunk(out: ByteArrayOutputStream, id: String, payload: ByteArray) {
        out.writeAscii(id)
        writeU32le(out, payload.size.toLong())
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0)
    }

    private fun ByteArrayOutputStream.writeAscii(s: String) {
        write(s.toByteArray(Charsets.US_ASCII))
    }

    private fun writeU32le(out: ByteArrayOutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 24) and 0xFF).toInt())
    }

    private fun writeU24le(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
    }

    internal fun fourCc(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    internal fun fourCcEquals(bytes: ByteArray, offset: Int, expected: String): Boolean =
        fourCc(bytes, offset) == expected

    internal fun u32le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    internal fun u24le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
}

/** Parsed view of an animated WebP file: frame timing without decoding. */
class AnimatedWebPInfo(
    val frameCount: Int,
    val frameDurationsMs: List<Int>,
    val width: Int,
    val height: Int
) {
    val totalDurationMs: Int get() = frameDurationsMs.sum()
}

object AnimatedWebPReader {

    /** Parses chunk structure only (fast, no pixel decoding). */
    fun parseInfo(bytes: ByteArray): AnimatedWebPInfo? {
        if (bytes.size < 20) return null
        if (!AnimatedWebPWriter.fourCcEquals(bytes, 0, "RIFF") ||
            !AnimatedWebPWriter.fourCcEquals(bytes, 8, "WEBP")
        ) return null
        var offset = 12
        var width = 0
        var height = 0
        val durations = ArrayList<Int>()
        while (offset + 8 <= bytes.size) {
            val id = AnimatedWebPWriter.fourCc(bytes, offset)
            val size = AnimatedWebPWriter.u32le(bytes, offset + 4).toInt()
            if (size < 0 || offset + 8 + size > bytes.size) break
            when (id) {
                "VP8X" -> {
                    if (size >= 10) {
                        width = AnimatedWebPWriter.u24le(bytes, offset + 8 + 4) + 1
                        height = AnimatedWebPWriter.u24le(bytes, offset + 8 + 7) + 1
                    }
                }
                "ANMF" -> {
                    if (size >= 16) {
                        durations.add(AnimatedWebPWriter.u24le(bytes, offset + 8 + 12))
                        if (width == 0) {
                            width = AnimatedWebPWriter.u24le(bytes, offset + 8 + 6) + 1
                            height = AnimatedWebPWriter.u24le(bytes, offset + 8 + 9) + 1
                        }
                    }
                }
            }
            offset += 8 + size + (size and 1)
        }
        if (durations.isEmpty()) return null
        return AnimatedWebPInfo(durations.size, durations, width, height)
    }

    fun parseInfo(file: File): AnimatedWebPInfo? =
        try {
            if (file.exists()) parseInfo(file.readBytes()) else null
        } catch (e: Exception) {
            null
        }

    /**
     * Decodes every frame to a Bitmap by re-wrapping each ANMF frame's
     * image chunks in a minimal standalone WebP file for BitmapFactory.
     * Returns the frames and the per-frame duration in ms (0 if unknown).
     */
    fun decodeFrames(bytes: ByteArray): Pair<List<Bitmap>, Int> {
        val frames = ArrayList<Bitmap>()
        if (bytes.size < 20) return frames to 0
        if (!AnimatedWebPWriter.fourCcEquals(bytes, 0, "RIFF") ||
            !AnimatedWebPWriter.fourCcEquals(bytes, 8, "WEBP")
        ) return frames to 0
        var offset = 12
        var durationMs = 0
        while (offset + 8 <= bytes.size) {
            val id = AnimatedWebPWriter.fourCc(bytes, offset)
            val size = AnimatedWebPWriter.u32le(bytes, offset + 4).toInt()
            if (size < 0 || offset + 8 + size > bytes.size) break
            if (id == "ANMF" && size >= 16) {
                val headerStart = offset + 8
                if (durationMs == 0) {
                    durationMs = AnimatedWebPWriter.u24le(bytes, headerStart + 12)
                }
                val frameWidth = AnimatedWebPWriter.u24le(bytes, headerStart + 6) + 1
                val frameHeight = AnimatedWebPWriter.u24le(bytes, headerStart + 9) + 1
                var sub = headerStart + 16
                val end = headerStart + size
                var imageId: String? = null
                var imageData: ByteArray? = null
                var alphaData: ByteArray? = null
                while (sub + 8 <= end) {
                    val subId = AnimatedWebPWriter.fourCc(bytes, sub)
                    val subSize = AnimatedWebPWriter.u32le(bytes, sub + 4).toInt()
                    if (subSize < 0 || sub + 8 + subSize > end) break
                    val payload = bytes.copyOfRange(sub + 8, sub + 8 + subSize)
                    when (subId) {
                        "VP8 ", "VP8L" -> {
                            imageId = subId
                            imageData = payload
                        }
                        "ALPH" -> alphaData = payload
                    }
                    sub += 8 + subSize + (subSize and 1)
                }
                val imgId = imageId
                val imgData = imageData
                if (imgId != null && imgData != null) {
                    val standalone = wrapStandaloneWebP(imgId, imgData, alphaData, frameWidth, frameHeight)
                    val bmp = BitmapFactory.decodeByteArray(standalone, 0, standalone.size)
                    if (bmp != null) frames.add(bmp)
                }
            }
            offset += 8 + size + (size and 1)
        }
        return frames to durationMs
    }

    fun decodeFrames(file: File): Pair<List<Bitmap>, Int> =
        try {
            if (file.exists()) decodeFrames(file.readBytes()) else (emptyList<Bitmap>() to 0)
        } catch (e: Exception) {
            emptyList<Bitmap>() to 0
        }

    /** Builds a minimal single-frame WebP file around one image chunk. */
    private fun wrapStandaloneWebP(
        imageChunkId: String,
        imageData: ByteArray,
        alphaData: ByteArray?,
        width: Int,
        height: Int
    ): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        if (alphaData != null) {
            // Extended header so the decoder applies the ALPH chunk.
            val vp8x = ByteArrayOutputStream()
            vp8x.write(0x10) // alpha flag only
            vp8x.write(0); vp8x.write(0); vp8x.write(0)
            writeU24(vp8x, width - 1)
            writeU24(vp8x, height - 1)
            writeChunk(body, "VP8X", vp8x.toByteArray())
            writeChunk(body, "ALPH", alphaData)
        }
        writeChunk(body, imageChunkId, imageData)
        val bodyBytes = body.toByteArray()
        val riff = ByteArrayOutputStream()
        riff.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeU32(riff, bodyBytes.size.toLong())
        riff.write(bodyBytes)
        if (riff.size() % 2 == 1) riff.write(0)
        return riff.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, id: String, payload: ByteArray) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        writeU32(out, payload.size.toLong())
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 24) and 0xFF).toInt())
    }

    private fun writeU24(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
    }
}
