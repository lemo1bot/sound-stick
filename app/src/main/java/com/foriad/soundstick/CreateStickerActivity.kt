package com.foriad.soundstick

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.foriad.soundstick.databinding.ActivityCreateBinding
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CreateStickerActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_ADD_WHATSAPP = 2001
        private const val REQUEST_ADD_WHATSAPP_BUSINESS = 2002
    }

    private lateinit var binding: ActivityCreateBinding
    private val executor = Executors.newSingleThreadExecutor()

    // Sound work runs on its OWN executor so a tiny job (e.g. generating a
    // built-in effect) never queues behind slow preview frame sampling.
    private val soundExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var baseBitmap: Bitmap? = null
    private var videoFile: File? = null
    private var soundFile: File? = null
    private var lastNoSoundToastMs = 0L

    // ---- Video (animated sticker) mode state ----
    private var videoMode = false
    private var videoDurationMs = 0L
    private var trimStartMs = 0L
    private var animLengthSec = 3
    private var zoom = 1.0f
    private var cropX = 0f // -1..1
    private var cropY = 0f // -1..1
    private var previewFrames: List<Bitmap> = emptyList()
    private var previewIndex = 0
    private var previewGeneration = 0

    // Preview sampling coalescing: at most ONE sampling run is ever in
    // flight; extra requests just flag that the latest parameters still
    // need sampling when the current run finishes.
    private val samplingBusy = AtomicBoolean(false)
    private val samplingPending = AtomicBoolean(false)

    // ---- Live player preview (primary video preview) ----
    // A real MediaPlayer on a TextureView plays the trimmed segment
    // smoothly; the sampled-frames loop above remains as the fallback if
    // the player cannot run on this device.
    private var previewPlayer: MediaPlayer? = null
    private var previewSurface: Surface? = null
    private var playerActive = false
    private var playerPrepared = false
    private var playerVideoWidth = 0
    private var playerVideoHeight = 0

    /** Loops the player inside [trimStartMs, trimStartMs + segmentLength]. */
    private val segmentWatcher = object : Runnable {
        override fun run() {
            val player = previewPlayer
            if (player == null || !playerPrepared) return
            try {
                if (player.isPlaying) {
                    val pos = player.currentPosition.toLong()
                    val endMs = trimStartMs + segmentLengthMs()
                    if (pos >= endMs || pos < trimStartMs) {
                        player.seekTo(trimStartMs.toInt())
                    }
                }
            } catch (e: Exception) {
                // Player raced with a release; the watcher stops with it.
                return
            }
            handler.postDelayed(this, 50)
        }
    }

    /** Debounced seek used while the trim-start slider is being dragged. */
    private val playerSeek = Runnable {
        val player = previewPlayer ?: return@Runnable
        if (playerPrepared) {
            try {
                player.seekTo(trimStartMs.toInt())
            } catch (e: Exception) {
                // ignore: player is being torn down
            }
        }
    }

    private val previewCycle = object : Runnable {
        override fun run() {
            if (playerActive) return
            if (!videoMode || previewFrames.size < 2) return
            previewIndex = (previewIndex + 1) % previewFrames.size
            binding.imgPreview.setImageBitmap(previewFrames[previewIndex])
            val frameMs = (segmentLengthMs() / previewFrames.size).coerceIn(40, 1000)
            handler.postDelayed(this, frameMs.toLong())
        }
    }

    private val previewResample = Runnable { samplePreviewFrames() }

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartMs = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pulse: ScaleAnimation? = null

    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) loadPhoto(uri) }

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) loadVideo(uri) }

    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) loadAudio(uri) }

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else toast("Microphone permission is needed to record")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }

        binding.btnPickPhoto.setOnClickListener { pickPhoto.launch("image/*") }
        binding.btnPickVideo.setOnClickListener { pickVideo.launch("video/*") }
        binding.btnPickAudio.setOnClickListener { pickAudio.launch("audio/*") }
        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnEffects.setOnClickListener { showEffects() }
        binding.btnTts.setOnClickListener { showTtsDialog() }
        binding.btnVideoSound.setOnClickListener { useVideoSound() }
        binding.btnPlaySound.setOnClickListener { playCurrentSound() }
        binding.imgPreview.setOnClickListener { onPreviewTapped() }
        // The TextureView stacks above imgPreview in video mode, so it
        // must forward taps to the same handler.
        binding.texturePreview.setOnClickListener { onPreviewTapped() }
        binding.texturePreview.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture, width: Int, height: Int
                ) {
                    if (videoMode && videoFile != null) setupPlayer()
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture, width: Int, height: Int
                ) {
                    applyPlayerTransform()
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    releasePlayer()
                    if (videoMode) {
                        binding.texturePreview.visibility = View.GONE
                        samplePreviewFrames()
                    }
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        binding.btnSave.setOnClickListener { choosePackAndSave() }

        binding.editCaption.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (videoMode) {
                    // Debounced: re-render the animated preview with the caption.
                    handler.removeCallbacks(previewResample)
                    handler.postDelayed(previewResample, 700)
                } else {
                    refreshPreview()
                }
            }
        })

        setupVideoControls()
    }

    override fun onPause() {
        super.onPause()
        stopSegmentWatcher()
        if (playerPrepared) {
            try {
                previewPlayer?.pause()
            } catch (e: Exception) {
                // ignore: player is being torn down
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (videoMode && playerActive && playerPrepared && previewPlayer != null) {
            try {
                previewPlayer?.start()
                startSegmentWatcher()
            } catch (e: Exception) {
                // ignore: player is being torn down
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPulse()
        handler.removeCallbacks(previewCycle)
        handler.removeCallbacks(previewResample)
        handler.removeCallbacks(playerSeek)
        releasePlayer()
        SoundPlayer.stop()
        stopRecording(cancel = true)
        tts?.shutdown()
        executor.shutdownNow()
        soundExecutor.shutdownNow()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------- Image sources ----------------

    private fun loadPhoto(uri: Uri) {
        executor.execute {
            try {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                if (bmp == null) {
                    runOnUiThread { toast("Could not load that photo") }
                    return@execute
                }
                baseBitmap = cropSquare(bmp)
                videoFile = null
                runOnUiThread {
                    exitVideoMode()
                    binding.btnVideoSound.visibility = View.GONE
                    refreshPreview()
                    toast("Photo added. Now add a sound!")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Could not load that photo") }
            }
        }
    }

    private fun loadVideo(uri: Uri) {
        // A long video can be hundreds of MB behind a content:// Uri, and
        // frame extraction from such Uris is unreliable (frames come back
        // null, so the preview never appears). Copy it to a local file
        // first and do ALL video work from the file path.
        toast("Loading video…")
        executor.execute {
            try {
                val ext = AudioTools.videoExtensionFor(this@CreateStickerActivity, uri)
                val local = File(cacheDir, "picked_video.$ext")
                if (!AudioTools.copyUriToFile(this@CreateStickerActivity, uri, local)) {
                    runOnUiThread { toast("Could not load that video") }
                    return@execute
                }
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(local.absolutePath)
                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                retriever.release()
                if (durationMs <= 0L) {
                    runOnUiThread { toast("Could not read that video") }
                    return@execute
                }
                runOnUiThread {
                    videoMode = true
                    videoFile = local
                    baseBitmap = null
                    videoDurationMs = durationMs
                    trimStartMs = 0L
                    binding.layoutVideoControls.visibility = View.VISIBLE
                    binding.btnVideoSound.visibility = View.VISIBLE
                    binding.seekTrimStart.max =
                        (durationMs - 500L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    binding.seekTrimStart.progress = 0
                    updateTrimLabels()
                    // Primary preview: the live player. The sampled-frames
                    // loop also starts, as instant stills + fallback if the
                    // player fails on this device.
                    setupPlayer()
                    samplePreviewFrames()
                    toast("Video added — trim & crop it, then add a sound!")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Could not load that video") }
            }
        }
    }

    private fun exitVideoMode() {
        videoMode = false
        previewGeneration++
        previewFrames = emptyList()
        handler.removeCallbacks(previewCycle)
        releasePlayer()
        binding.texturePreview.visibility = View.GONE
        binding.layoutVideoControls.visibility = View.GONE
    }

    // ---------------- Live player preview ----------------

    /**
     * Creates (or recreates) the MediaPlayer for the local video file on
     * the TextureView's surface. Safe to call when no surface exists yet —
     * the SurfaceTextureListener retries when one becomes available.
     */
    private fun setupPlayer() {
        val video = videoFile ?: return
        val texture = binding.texturePreview.surfaceTexture ?: return
        releasePlayer()
        val surface = Surface(texture)
        previewSurface = surface
        val player = MediaPlayer()
        previewPlayer = player
        try {
            player.setDataSource(video.absolutePath)
            player.setSurface(surface)
            player.setVolume(0f, 0f)
            player.setOnPreparedListener { mp ->
                if (previewPlayer !== mp) return@setOnPreparedListener
                playerPrepared = true
                playerVideoWidth = mp.videoWidth
                playerVideoHeight = mp.videoHeight
                applyPlayerTransform()
                // The view may not be laid out yet at prepare time.
                binding.texturePreview.post { applyPlayerTransform() }
                try {
                    mp.seekTo(trimStartMs.toInt())
                    mp.start()
                } catch (e: Exception) {
                    // ignore: player is being torn down
                }
                startSegmentWatcher()
            }
            player.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    onPlayerRendering()
                }
                false
            }
            player.setOnErrorListener { _, _, _ ->
                onPlayerError()
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            onPlayerError()
        }
    }

    /** First frame is on screen: swap the sampled-frames loop for the player. */
    private fun onPlayerRendering() {
        if (!videoMode) return
        playerActive = true
        handler.removeCallbacks(previewCycle)
        binding.texturePreview.visibility = View.VISIBLE
    }

    /** Player failed: hide it and fall back to the sampled-frames loop. */
    private fun onPlayerError() {
        releasePlayer()
        binding.texturePreview.visibility = View.GONE
        if (videoMode) samplePreviewFrames()
    }

    private fun releasePlayer() {
        stopSegmentWatcher()
        handler.removeCallbacks(playerSeek)
        try {
            previewPlayer?.stop()
        } catch (e: Exception) {
            // ignore: not all states allow stop()
        }
        previewPlayer?.release()
        previewPlayer = null
        previewSurface?.release()
        previewSurface = null
        playerActive = false
        playerPrepared = false
        playerVideoWidth = 0
        playerVideoHeight = 0
    }

    private fun startSegmentWatcher() {
        handler.removeCallbacks(segmentWatcher)
        handler.postDelayed(segmentWatcher, 50)
    }

    private fun stopSegmentWatcher() {
        handler.removeCallbacks(segmentWatcher)
    }

    private fun schedulePlayerSeek() {
        handler.removeCallbacks(playerSeek)
        handler.postDelayed(playerSeek, 150)
    }

    /**
     * Applies the export crop (center-crop + zoom + X/Y pan) to the
     * TextureView. The TextureView's default transform stretches the
     * video buffer to fill the view exactly, so we scale about the view
     * center by the cover factor (relative to that default fit) times the
     * zoom, then translate by the crop offsets scaled to the overflow —
     * the same math [processFrame] applies to exported frames.
     */
    private fun applyPlayerTransform() {
        val view = binding.texturePreview
        val viewW = view.width
        val viewH = view.height
        if (viewW == 0 || viewH == 0 || playerVideoWidth == 0 || playerVideoHeight == 0) return
        val fitX = viewW.toFloat() / playerVideoWidth
        val fitY = viewH.toFloat() / playerVideoHeight
        val cover = maxOf(fitX, fitY)
        val relX = cover / fitX
        val relY = cover / fitY
        val contentW = playerVideoWidth * cover
        val contentH = playerVideoHeight * cover
        val overflowX = (contentW * zoom - viewW) / 2f
        val overflowY = (contentH * zoom - viewH) / 2f
        val matrix = Matrix()
        matrix.setScale(relX * zoom, relY * zoom, viewW / 2f, viewH / 2f)
        matrix.postTranslate(-cropX * overflowX, -cropY * overflowY)
        view.setTransform(matrix)
    }

    private fun cropSquare(bmp: Bitmap): Bitmap {
        val size = minOf(bmp.width, bmp.height)
        val x = (bmp.width - size) / 2
        val y = (bmp.height - size) / 2
        return Bitmap.createBitmap(bmp, x, y, size, size)
    }

    // ---------------- Video mode: trim & crop ----------------

    private fun segmentLengthMs(): Int {
        val wanted = animLengthSec * 1000
        val remaining = (videoDurationMs - trimStartMs).toInt()
        return minOf(wanted, remaining).coerceAtLeast(200)
    }

    private fun setupVideoControls() {
        binding.seekTrimStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                trimStartMs = progress.toLong()
                updateTrimLabels()
                if (playerActive) {
                    schedulePlayerSeek()
                } else {
                    schedulePreviewResample()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (playerActive) {
                    handler.removeCallbacks(playerSeek)
                    playerSeek.run()
                } else {
                    samplePreviewFrames()
                }
            }
        })
        binding.rgLength.setOnCheckedChangeListener { _, checkedId ->
            animLengthSec = when (checkedId) {
                binding.rbLen1.id -> 1
                binding.rbLen2.id -> 2
                else -> 3
            }
            updateTrimLabels()
            // With the player active the segment watcher reads the new
            // length live; only the fallback loop needs fresh frames.
            if (!playerActive) samplePreviewFrames()
        }
        binding.seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                zoom = 1.0f + progress / 100f
                if (playerActive) {
                    applyPlayerTransform()
                } else {
                    schedulePreviewResample()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (!playerActive) samplePreviewFrames()
            }
        })
        binding.seekCropX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                cropX = (progress - 100) / 100f
                if (playerActive) {
                    applyPlayerTransform()
                } else {
                    schedulePreviewResample()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (!playerActive) samplePreviewFrames()
            }
        })
        binding.seekCropY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                cropY = (progress - 100) / 100f
                if (playerActive) {
                    applyPlayerTransform()
                } else {
                    schedulePreviewResample()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (!playerActive) samplePreviewFrames()
            }
        })
    }

    private fun updateTrimLabels() {
        binding.txtTrimStart.text =
            "Start: %.1fs".format(trimStartMs / 1000f)
        binding.txtTrimInfo.text =
            "Video is %.1fs — your animated sticker uses %ds from the start point."
                .format(videoDurationMs / 1000f, animLengthSec)
    }

    /** Debounced resample (~200ms) so sliders feel live without flooding the sampler. */
    private fun schedulePreviewResample() {
        handler.removeCallbacks(previewResample)
        handler.postDelayed(previewResample, 200)
    }

    /** Samples low-res frames of the chosen segment and loops them as a preview. */
    private fun samplePreviewFrames() {
        val video = videoFile ?: return
        // Coalesce: if a sampling run is already in flight, do NOT queue
        // another one behind it — just remember the latest parameters
        // still need sampling once it finishes.
        if (!samplingBusy.compareAndSet(false, true)) {
            samplingPending.set(true)
            return
        }
        val generation = ++previewGeneration
        val caption = binding.editCaption.text?.toString()?.trim().orEmpty()
        val start = trimStartMs
        val length = segmentLengthMs()
        // ~8 frames total regardless of segment length keeps sampling cheap.
        val fps = maxOf(2, 8000 / length).coerceAtMost(6)
        executor.execute {
            // Fast path: show ONE frame at the segment start right away so
            // the preview never sits on the placeholder while the full
            // looping set is being sampled.
            val firstFrame = sampleSingleFrame(video, start, caption)
            if (firstFrame != null) {
                runOnUiThread {
                    if (generation == previewGeneration && videoMode) {
                        binding.imgPreview.setImageBitmap(firstFrame)
                    }
                }
            }
            val frames = sampleFrames(
                video, start, length, fps = fps, size = 192, caption = caption,
                frameGrabber = { r, t -> getPreviewFrameWithFallback(r, t) }
            )
            runOnUiThread {
                try {
                    if (generation == previewGeneration && videoMode) {
                        if (frames.isEmpty()) {
                            toast("Could not preview that part of the video")
                        } else {
                            previewFrames = frames
                            previewIndex = 0
                            binding.imgPreview.setImageBitmap(frames[0])
                            handler.removeCallbacks(previewCycle)
                            if (frames.size > 1 && !playerActive) {
                                handler.postDelayed(previewCycle, 120)
                            }
                        }
                    }
                } finally {
                    finishSampling()
                }
            }
        }
    }

    /** Releases the sampling slot; re-samples once if sliders moved meanwhile. */
    private fun finishSampling() {
        samplingBusy.set(false)
        if (samplingPending.compareAndSet(true, false)) {
            samplePreviewFrames()
        }
    }

    /** Extracts and processes a single frame at [timeMs] from the local file. */
    private fun sampleSingleFrame(video: File, timeMs: Long, caption: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val frame = getPreviewFrameWithFallback(retriever, timeMs * 1000L) ?: return null
            processFrame(frame, 192, caption)
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /**
     * Frame getter with a fallback chain: some devices/codecs return null
     * for OPTION_CLOSEST on timestamps they cannot seek to exactly, so fall
     * back to the sync-frame options before giving up on the timestamp.
     */
    private fun getFrameWithFallback(
        retriever: MediaMetadataRetriever,
        timeUs: Long
    ): Bitmap? {
        val options = intArrayOf(
            MediaMetadataRetriever.OPTION_CLOSEST,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            MediaMetadataRetriever.OPTION_PREVIOUS_SYNC
        )
        for (option in options) {
            try {
                retriever.getFrameAtTime(timeUs, option)?.let { return it }
            } catch (e: Exception) {
                // try the next option
            }
        }
        return null
    }

    /**
     * Preview-only frame getter: cheapest seek first. Preview frames are
     * small and disposable, so prefer the fast sync-frame option; the
     * export path keeps using [getFrameWithFallback] (quality first).
     */
    private fun getPreviewFrameWithFallback(
        retriever: MediaMetadataRetriever,
        timeUs: Long
    ): Bitmap? {
        val options = intArrayOf(
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            MediaMetadataRetriever.OPTION_CLOSEST,
            MediaMetadataRetriever.OPTION_PREVIOUS_SYNC
        )
        for (option in options) {
            try {
                retriever.getFrameAtTime(timeUs, option)?.let { return it }
            } catch (e: Exception) {
                // try the next option
            }
        }
        return null
    }

    /**
     * Samples [fps] frames per second from the video segment, applying the
     * square crop (zoom + X/Y offset) and the caption to every frame.
     */
    private fun sampleFrames(
        video: File,
        startMs: Long,
        lengthMs: Int,
        fps: Int,
        size: Int,
        caption: String,
        frameGrabber: (MediaMetadataRetriever, Long) -> Bitmap? =
            { r, t -> getFrameWithFallback(r, t) }
    ): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
        } catch (e: Exception) {
            return emptyList()
        }
        val count = maxOf(1, lengthMs * fps / 1000)
        val out = ArrayList<Bitmap>(count)
        var last: Bitmap? = null
        try {
            for (i in 0 until count) {
                val tMs = startMs + i * (1000 / fps)
                val frame = frameGrabber(retriever, tMs * 1000L)
                val src = frame ?: last ?: continue
                last = src
                out.add(processFrame(src, size, caption))
            }
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }
        return out
    }

    /** Applies the square crop (zoom + offsets), scales to [size], draws the caption. */
    private fun processFrame(src: Bitmap, size: Int, caption: String): Bitmap {
        val baseSide = minOf(src.width, src.height)
        val side = (baseSide / zoom).toInt().coerceAtLeast(16)
        val maxLeft = src.width - side
        val maxTop = src.height - side
        val centerLeft = maxLeft / 2f
        val centerTop = maxTop / 2f
        val left = (centerLeft + cropX * centerLeft).toInt().coerceIn(0, maxLeft)
        val top = (centerTop + cropY * centerTop).toInt().coerceIn(0, maxTop)
        val cropped = Bitmap.createBitmap(src, left, top, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
        return applyCaption(scaled, caption)
    }

    /** Draws the caption (yellow, black outline, bottom) onto a copy of [src]. */
    private fun applyCaption(src: Bitmap, caption: String): Bitmap {
        if (caption.isEmpty()) return src
        val size = src.width
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val canvas = Canvas(out)
        val scale = size / 512f
        var textSize = 46f * scale
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.STROKE
            color = Color.BLACK
            strokeWidth = 9f * scale
            textAlign = Paint.Align.CENTER
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.FILL
            color = Color.parseColor("#FFD500")
            textAlign = Paint.Align.CENTER
        }
        while (textSize > 16f * scale) {
            stroke.textSize = textSize
            if (stroke.measureText(caption) <= size - 20f * scale) break
            textSize -= 3f * scale
        }
        fill.textSize = textSize
        stroke.textSize = textSize
        val y = size - 26f * scale
        canvas.drawText(caption, size / 2f, y, stroke)
        canvas.drawText(caption, size / 2f, y, fill)
        return out
    }

    // ---------------- Sound sources ----------------

    private fun setSound(file: File, label: String) {
        soundFile = file
        binding.txtSoundStatus.text = "Sound: $label"
    }

    private fun loadAudio(uri: Uri) {
        toast("Loading audio…")
        soundExecutor.execute {
            val ext = AudioTools.extensionFor(this@CreateStickerActivity, uri)
            val out = File(cacheDir, "picked_audio.$ext")
            val ok = AudioTools.copyUriToFile(this@CreateStickerActivity, uri, out)
            runOnUiThread {
                if (ok) setSound(out, "picked audio file")
                else toast("Could not load that audio file")
            }
        }
    }

    private fun useVideoSound() {
        val video = videoFile ?: return
        toast("Extracting the video's sound…")
        soundExecutor.execute {
            val out = File(cacheDir, "video_sound.m4a")
            val ok = AudioTools.extractVideoAudio(video, out)
            runOnUiThread {
                if (ok) setSound(out, "video's own sound")
                else toast("That video has no sound track")
            }
        }
    }

    private fun toggleRecording() {
        if (recorder != null) {
            stopRecording(cancel = false)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecording()
    }

    private fun startRecording() {
        try {
            val file = File(cacheDir, "voice_record.m4a")
            recordingFile = file
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recordingStartMs = System.currentTimeMillis()
            binding.btnRecord.text = "⏹ Stop Recording (0s)"
            tickRecordingTimer()
        } catch (e: Exception) {
            recorder = null
            toast("Could not start recording")
        }
    }

    private fun tickRecordingTimer() {
        handler.postDelayed({
            if (recorder != null) {
                val secs = (System.currentTimeMillis() - recordingStartMs) / 1000
                if (secs >= 10) {
                    stopRecording(cancel = false)
                } else {
                    binding.btnRecord.text = "⏹ Stop Recording (${secs}s)"
                    tickRecordingTimer()
                }
            }
        }, 250)
    }

    private fun stopRecording(cancel: Boolean) {
        val rec = recorder ?: return
        recorder = null
        try {
            rec.stop()
        } catch (e: Exception) {
            // too short / failed recording
        }
        rec.release()
        binding.btnRecord.text = "🎙 Record Voice (max 10s)"
        val file = recordingFile
        if (!cancel && file != null && file.exists() && file.length() > 0) {
            setSound(file, "your recorded voice")
        }
    }

    private fun showEffects() {
        val names = EffectsSynth.EFFECTS.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Built-in sound effects")
            .setItems(names) { _, which ->
                val effect = EffectsSynth.EFFECTS[which]
                soundExecutor.execute {
                    val file = EffectsSynth.effectFile(cacheDir, effect)
                    runOnUiThread {
                        setSound(file, "effect: ${effect.name}")
                        playCurrentSound()
                    }
                }
            }
            .show()
    }

    private fun showTtsDialog() {
        if (!ttsReady) {
            toast("Text-to-Speech is still starting, try again in a second")
            return
        }
        val input = EditText(this).apply {
            hint = "Type what the sticker should say"
        }
        AlertDialog.Builder(this)
            .setTitle("Text-to-Speech")
            .setView(input)
            .setPositiveButton("Create Voice") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    toast("Type something first")
                } else {
                    speakToFile(text)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun speakToFile(text: String) {
        val engine = tts ?: return
        val out = File(cacheDir, "tts_voice.wav")
        if (out.exists()) out.delete()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    if (out.exists() && out.length() > 0) {
                        setSound(out, "text-to-speech voice")
                        playCurrentSound()
                    } else {
                        toast("Text-to-Speech failed")
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { toast("Text-to-Speech failed") }
            }
        })
        val result = engine.synthesizeToFile(text, Bundle(), out, "soundstick_tts")
        if (result != TextToSpeech.SUCCESS) toast("Text-to-Speech failed")
        else toast("Making the voice…")
    }

    // ---------------- Preview ----------------

    /**
     * Preview tap: plays the sound if one is attached. With no sound in
     * video mode it silently keeps/restarts the preview loop (no toast);
     * otherwise it nudges "Add a sound first", throttled to once per 4s.
     */
    private fun onPreviewTapped() {
        val file = soundFile
        if (file != null && file.exists()) {
            playCurrentSound()
            return
        }
        if (videoMode) {
            if (!playerActive && previewFrames.size > 1) {
                handler.removeCallbacks(previewCycle)
                handler.postDelayed(previewCycle, 120)
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastNoSoundToastMs >= 4000) {
            lastNoSoundToastMs = now
            toast("Add a sound first")
        }
    }

    private fun playCurrentSound() {
        val file = soundFile
        if (file == null || !file.exists()) {
            toast("Add a sound first")
            return
        }
        startPulse()
        SoundPlayer.play(file) { stopPulse() }
    }

    private fun startPulse() {
        stopPulse()
        pulse = ScaleAnimation(
            1f, 1.12f, 1f, 1.12f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 220
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        binding.imgPreview.startAnimation(pulse)
    }

    private fun stopPulse() {
        pulse = null
        binding.imgPreview.clearAnimation()
        binding.imgPreview.scaleX = 1f
        binding.imgPreview.scaleY = 1f
    }

    private fun refreshPreview() {
        val bmp = renderFinalBitmap() ?: return
        binding.imgPreview.setImageBitmap(bmp)
    }

    /** Final 512x512 static sticker bitmap: square photo + caption. */
    private fun renderFinalBitmap(): Bitmap? {
        val base = baseBitmap ?: return null
        val out = Bitmap.createScaledBitmap(base, 512, 512, true)
        val caption = binding.editCaption.text?.toString()?.trim().orEmpty()
        return applyCaption(out, caption)
    }

    // ---------------- Save ----------------

    private fun choosePackAndSave() {
        val animated = videoMode
        if (animated && videoFile == null) {
            toast("Pick a video first")
            return
        }
        if (!animated && baseBitmap == null) {
            toast("Pick a photo or a video first")
            return
        }
        // WhatsApp packs are all-static or all-animated: only offer packs
        // whose type matches this sticker (plus "New pack").
        val packs = PackStore.loadPacks(this).filter { it.accepts(animated) }
        val typeLabel = if (animated) "animated" else "static"
        val labels = packs.map { "${it.name} (${it.stickers.size})" } + "＋ New pack…"
        AlertDialog.Builder(this)
            .setTitle("Save $typeLabel sticker to pack")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == packs.size) {
                    showNewPackDialog(animated)
                } else {
                    saveToPack(packs[which], animated)
                }
            }
            .show()
    }

    private fun showNewPackDialog(animated: Boolean) {
        val input = EditText(this).apply { hint = "Pack name" }
        AlertDialog.Builder(this)
            .setTitle("New pack")
            .setView(input)
            .setPositiveButton("Create & Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("Give the pack a name")
                } else {
                    saveToPack(PackStore.createPack(this, name), animated)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveToPack(pack: StickerPack, animated: Boolean) {
        val audio = soundFile
        if (!animated) {
            val bitmap = renderFinalBitmap() ?: return
            toast("Saving sticker…")
            executor.execute {
                PackStore.addSticker(this@CreateStickerActivity, pack, bitmap, audio)
                runOnUiThread { showSavedDialog(pack) }
            }
            return
        }
        val video = videoFile ?: return
        val caption = binding.editCaption.text?.toString()?.trim().orEmpty()
        val start = trimStartMs
        val fullLength = segmentLengthMs()
        toast("Making your animated sticker…")
        executor.execute {
            val encoded = encodeAnimatedSticker(video, start, fullLength, caption)
            if (encoded == null) {
                runOnUiThread { toast("Could not make an animated sticker from that video") }
                return@execute
            }
            val (bytes, firstFrame) = encoded
            PackStore.addAnimatedSticker(
                this@CreateStickerActivity, pack, bytes, audio, firstFrame
            )
            runOnUiThread { showSavedDialog(pack) }
        }
    }

    /**
     * Encodes the trimmed/cropped segment as an animated WebP <= 500 KB.
     * Tries progressively lower quality, then fewer frames per second,
     * then a shorter segment, until it fits WhatsApp's limit.
     */
    private fun encodeAnimatedSticker(
        video: File,
        startMs: Long,
        fullLengthMs: Int,
        caption: String
    ): Pair<ByteArray, Bitmap?>? {
        data class Attempt(val fps: Int, val quality: Int, val lengthScale: Double)

        val attempts = listOf(
            Attempt(10, 75, 1.0),
            Attempt(10, 55, 1.0),
            Attempt(8, 50, 1.0),
            Attempt(6, 45, 1.0),
            Attempt(5, 40, 1.0),
            Attempt(5, 40, 0.7),
            Attempt(5, 35, 0.5)
        )
        for (attempt in attempts) {
            val lengthMs = (fullLengthMs * attempt.lengthScale).toInt().coerceAtLeast(200)
            val frames = sampleFrames(video, startMs, lengthMs, attempt.fps, 512, caption)
            if (frames.isEmpty()) continue
            val bytes = AnimatedWebPWriter.encode(frames, 1000 / attempt.fps, attempt.quality)
                ?: continue
            if (bytes.size <= PackStore.ANIMATED_MAX_BYTES) {
                return bytes to frames.firstOrNull()
            }
        }
        return null
    }

    // ---------------- After save: add the pack to WhatsApp ----------------

    private fun showSavedDialog(pack: StickerPack) {
        val fresh = PackStore.getPack(this, pack.id) ?: pack
        val message = if (fresh.stickers.size < 3) {
            "Saved to “${fresh.name}” (${fresh.stickers.size} sticker(s)). " +
                "WhatsApp needs at least 3 stickers in a pack before a pack can be added, " +
                "so add ${3 - fresh.stickers.size} more to this pack first."
        } else {
            "Saved to “${fresh.name}”. You can add the whole pack to WhatsApp right now."
        }
        AlertDialog.Builder(this)
            .setTitle("Sticker saved! 🎉")
            .setMessage(message)
            .setPositiveButton("Add pack to WhatsApp") { _, _ ->
                addPackToWhatsApp(fresh, WhatsAppHelper.WHATSAPP_PACKAGE, REQUEST_ADD_WHATSAPP)
            }
            .setNeutralButton("Add pack to WhatsApp Business") { _, _ ->
                addPackToWhatsApp(
                    fresh,
                    WhatsAppHelper.WHATSAPP_BUSINESS_PACKAGE,
                    REQUEST_ADD_WHATSAPP_BUSINESS
                )
            }
            .setNegativeButton("Done") { _, _ ->
                // Calm interstitial timing applies (see InterstitialHelper).
                InterstitialHelper.maybeShow(this) { finish() }
            }
            .show()
    }

    private fun addPackToWhatsApp(pack: StickerPack, packageName: String, requestCode: Int) {
        val appName = WhatsAppHelper.displayName(packageName)
        if (!WhatsAppHelper.isInstalled(this, packageName)) {
            Toast.makeText(
                this, "$appName is not installed on this phone.", Toast.LENGTH_LONG
            ).show()
            return
        }
        val problem = WhatsAppHelper.validatePack(this, pack)
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(WhatsAppHelper.buildIntent(pack, packageName), requestCode)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this, "$appName is not installed on this phone.", Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val packageName = when (requestCode) {
            REQUEST_ADD_WHATSAPP -> WhatsAppHelper.WHATSAPP_PACKAGE
            REQUEST_ADD_WHATSAPP_BUSINESS -> WhatsAppHelper.WHATSAPP_BUSINESS_PACKAGE
            else -> return
        }
        val message = WhatsAppHelper.resultMessage(resultCode, data, packageName)
        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
}
