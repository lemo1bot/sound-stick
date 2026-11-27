package com.foriad.soundstick

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/** Copies media streams between containers (no re-encoding). */
object AudioTools {

    /**
     * Extracts the audio track of a video into an .m4a (AAC in MP4
     * container) so the video's own sound can be stored with the sticker.
     * Returns true on success.
     */
    fun extractVideoAudio(context: Context, videoUri: Uri, outFile: File): Boolean =
        extractAudio(outFile) { it.setDataSource(context, videoUri, null) }

    /**
     * Same extraction, but from a local video file (path-based data
     * source). Path-based sources are far more reliable than content://
     * Uris for MediaExtractor, so the app prefers this overload.
     */
    fun extractVideoAudio(videoFile: File, outFile: File): Boolean =
        extractAudio(outFile) { it.setDataSource(videoFile.absolutePath) }

    private fun extractAudio(outFile: File, setSource: (MediaExtractor) -> Unit): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            setSource(extractor)
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if ((format.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    audioTrack = i
                    break
                }
            }
            if (audioTrack < 0) return false
            extractor.selectTrack(audioTrack)
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(extractor.getTrackFormat(audioTrack))
            muxer.start()
            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            var firstPts = -1L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (firstPts < 0) firstPts = pts
                info.offset = 0
                info.size = size
                info.presentationTimeUs = maxOf(0, pts - firstPts)
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            try {
                muxer?.stop()
            } catch (e: Exception) {
                // ignore
            }
            muxer?.release()
            extractor.release()
        }
    }

    /** Copies the bytes behind a content Uri into a local file. */
    fun copyUriToFile(context: Context, uri: Uri, outFile: File): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            outFile.exists() && outFile.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    /** Best-effort file extension for a content Uri's mime type. */
    fun extensionFor(context: Context, uri: Uri): String {
        val mime = context.contentResolver.getType(uri) ?: return "m4a"
        return when {
            mime.contains("wav") -> "wav"
            mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
            mime.contains("ogg") -> "ogg"
            mime.contains("aac") || mime.contains("mp4") || mime.contains("m4a") -> "m4a"
            else -> "m4a"
        }
    }

    /** Best-effort file extension for a picked video's mime type. */
    fun videoExtensionFor(context: Context, uri: Uri): String {
        val mime = context.contentResolver.getType(uri) ?: return "mp4"
        return when {
            mime.contains("webm") -> "webm"
            mime.contains("3gpp") -> "3gp"
            mime.contains("quicktime") -> "mov"
            mime.contains("matroska") || mime.contains("mkv") -> "mkv"
            else -> "mp4"
        }
    }
}
