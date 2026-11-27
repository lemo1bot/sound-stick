package com.foriad.soundstick

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.foriad.soundstick.databinding.ActivityCreateBinding
import java.io.File
import java.util.concurrent.Executors

class CreateStickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var baseBitmap: Bitmap? = null
    private var videoUri: Uri? = null
    private var soundFile: File? = null

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
        binding.imgPreview.setOnClickListener { playCurrentSound() }
        binding.btnSave.setOnClickListener { choosePackAndSave() }

        binding.editCaption.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshPreview() }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPulse()
        SoundPlayer.stop()
        stopRecording(cancel = true)
        tts?.shutdown()
        executor.shutdownNow()
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
                videoUri = null
                runOnUiThread {
                    binding.btnVideoSound.visibility = android.view.View.GONE
                    refreshPreview()
                    toast("Photo added. Now add a sound!")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Could not load that photo") }
            }
        }
    }

    private fun loadVideo(uri: Uri) {
        executor.execute {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this@CreateStickerActivity, uri)
                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                val frame = retriever.getFrameAtTime(
                    durationMs * 500, // middle of the video, in microseconds
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                retriever.release()
                if (frame == null) {
                    runOnUiThread { toast("Could not get a frame from that video") }
                    return@execute
                }
                baseBitmap = cropSquare(frame)
                videoUri = uri
                runOnUiThread {
                    binding.btnVideoSound.visibility = android.view.View.VISIBLE
                    refreshPreview()
                    toast("Video frame added. You can use the video's own sound!")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Could not load that video") }
            }
        }
    }

    private fun cropSquare(bmp: Bitmap): Bitmap {
        val size = minOf(bmp.width, bmp.height)
        val x = (bmp.width - size) / 2
        val y = (bmp.height - size) / 2
        return Bitmap.createBitmap(bmp, x, y, size, size)
    }

    // ---------------- Sound sources ----------------

    private fun setSound(file: File, label: String) {
        soundFile = file
        binding.txtSoundStatus.text = "Sound: $label"
    }

    private fun loadAudio(uri: Uri) {
        toast("Loading audio…")
        executor.execute {
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
        val uri = videoUri ?: return
        toast("Extracting the video's sound…")
        executor.execute {
            val out = File(cacheDir, "video_sound.m4a")
            val ok = AudioTools.extractVideoAudio(this@CreateStickerActivity, uri, out)
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
                executor.execute {
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

    /** Final 512x512 sticker bitmap: square image + caption at the bottom. */
    private fun renderFinalBitmap(): Bitmap? {
        val base = baseBitmap ?: return null
        val out = Bitmap.createScaledBitmap(base, 512, 512, true)
            .copy(Bitmap.Config.ARGB_8888, true)
        val caption = binding.editCaption.text?.toString()?.trim().orEmpty()
        if (caption.isNotEmpty()) {
            val canvas = Canvas(out)
            var textSize = 46f
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT_BOLD
                style = Paint.Style.STROKE
                color = Color.BLACK
                strokeWidth = 9f
                textAlign = Paint.Align.CENTER
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT_BOLD
                style = Paint.Style.FILL
                color = Color.parseColor("#FFD500")
                textAlign = Paint.Align.CENTER
            }
            while (textSize > 16f) {
                stroke.textSize = textSize
                if (stroke.measureText(caption) <= 492f) break
                textSize -= 3f
            }
            fill.textSize = textSize
            stroke.textSize = textSize
            val y = 512f - 26f
            canvas.drawText(caption, 256f, y, stroke)
            canvas.drawText(caption, 256f, y, fill)
        }
        return out
    }

    // ---------------- Save ----------------

    private fun choosePackAndSave() {
        if (baseBitmap == null) {
            toast("Pick a photo or a video first")
            return
        }
        val packs = PackStore.loadPacks(this)
        val labels = packs.map { "${it.name} (${it.stickers.size})" } + "＋ New pack…"
        AlertDialog.Builder(this)
            .setTitle("Save sticker to pack")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == packs.size) {
                    showNewPackDialog()
                } else {
                    saveToPack(packs[which])
                }
            }
            .show()
    }

    private fun showNewPackDialog() {
        val input = EditText(this).apply { hint = "Pack name" }
        AlertDialog.Builder(this)
            .setTitle("New pack")
            .setView(input)
            .setPositiveButton("Create & Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("Give the pack a name")
                } else {
                    saveToPack(PackStore.createPack(this, name))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveToPack(pack: StickerPack) {
        val bitmap = renderFinalBitmap() ?: return
        val audio = soundFile
        toast("Saving sticker…")
        executor.execute {
            PackStore.addSticker(this@CreateStickerActivity, pack, bitmap, audio)
            runOnUiThread {
                Toast.makeText(
                    this@CreateStickerActivity,
                    "Sticker saved to “${pack.name}”",
                    Toast.LENGTH_SHORT
                ).show()
                // Calm interstitial timing applies (see InterstitialHelper).
                InterstitialHelper.maybeShow(this@CreateStickerActivity) { finish() }
            }
        }
    }
}
