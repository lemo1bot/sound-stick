package com.foriad.soundstick

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.foriad.soundstick.databinding.ActivityPackBinding
import com.foriad.soundstick.databinding.ItemStickerBinding
import com.google.android.gms.ads.AdRequest
import java.io.File
import java.util.concurrent.Executors

class PackActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACK_ID = "extra_pack_id"
        private const val REQUEST_ADD_TO_WHATSAPP = 1001
    }

    private lateinit var binding: ActivityPackBinding
    private lateinit var pack: StickerPack
    private val executor = Executors.newSingleThreadExecutor()
    private var lastTargetPackage: String = WhatsAppHelper.WHATSAPP_PACKAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val packId = intent.getStringExtra(EXTRA_PACK_ID) ?: ""
        val loaded = PackStore.getPack(this, packId)
        if (loaded == null) {
            Toast.makeText(this, "Pack not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        pack = loaded

        // Repair packs saved before the WhatsApp size rules were enforced
        // (oversized stickers / tray), so they can be added to WhatsApp.
        executor.execute { PackStore.healPack(this, loaded) }

        binding.txtPackTitle.text = pack.name
        binding.txtPackInfo.text =
            "${pack.stickers.size} stickers · by ${pack.publisher}" +
                (if (pack.isAnimated) " · animated" else "")

        binding.recyclerStickers.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerStickers.adapter = StickerAdapter()

        binding.btnAddWhatsApp.setOnClickListener {
            addToWhatsApp(WhatsAppHelper.WHATSAPP_PACKAGE)
        }
        binding.btnAddWhatsAppBusiness.setOnClickListener {
            addToWhatsApp(WhatsAppHelper.WHATSAPP_BUSINESS_PACKAGE)
        }
        binding.btnPublishPack.setOnClickListener { confirmPublishPack() }

        try {
            binding.adView.adUnitId = AdsConfig.BANNER_ID
            binding.adView.loadAd(AdRequest.Builder().build())
        } catch (e: Exception) {
            binding.adView.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SoundPlayer.stop()
        executor.shutdownNow()
    }

    private fun toastLong(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Official WhatsApp sticker-pack hand-off, targeted at one app. */
    private fun addToWhatsApp(packageName: String) {
        val appName = WhatsAppHelper.displayName(packageName)
        if (!WhatsAppHelper.isInstalled(this, packageName)) {
            toastLong("$appName is not installed on this phone.")
            return
        }
        // Validate everything up front so the user gets a clear reason
        // here instead of WhatsApp's opaque validation_error.
        val problem = WhatsAppHelper.validatePack(this, pack)
        if (problem != null) {
            toastLong(problem)
            return
        }
        lastTargetPackage = packageName
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                WhatsAppHelper.buildIntent(pack, packageName),
                REQUEST_ADD_TO_WHATSAPP
            )
        } catch (e: ActivityNotFoundException) {
            toastLong("$appName is not installed on this phone.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ADD_TO_WHATSAPP) {
            val message = WhatsAppHelper.resultMessage(resultCode, data, lastTargetPackage)
            if (message != null) toastLong(message)
        }
    }

    // ---------------- Community publishing ----------------

    private fun confirmPublishPack() {
        if (pack.stickers.isEmpty()) {
            toastLong("This pack has no stickers to publish yet.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Publish pack to Community?")
            .setMessage(
                "Anyone using SoundStick can see and use these stickers. " +
                    "All ${pack.stickers.size} sticker(s) in “${pack.name}” will be published."
            )
            .setPositiveButton("Publish") { _, _ -> publishPack() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun publishPack() {
        val snapshot = PackStore.getPack(this, pack.id) ?: pack
        toastLong("Publishing pack…")
        executor.execute {
            var published = 0
            for (sticker in snapshot.stickers) {
                if (CommunityApi.publishSticker(this, snapshot, sticker).isSuccess) {
                    published++
                }
            }
            runOnUiThread {
                if (published == snapshot.stickers.size) {
                    toastLong("Pack published to Community! 🌍")
                } else if (published > 0) {
                    toastLong("Published $published of ${snapshot.stickers.size} stickers — Community may be partly unavailable.")
                } else {
                    toastLong("Community is not available right now")
                }
            }
        }
    }

    private fun confirmPublishSticker(sticker: StickerItem) {
        AlertDialog.Builder(this)
            .setTitle("Publish sticker to Community?")
            .setMessage("Anyone using SoundStick can see and use this sticker.")
            .setPositiveButton("Publish") { _, _ ->
                toastLong("Publishing sticker…")
                executor.execute {
                    val result = CommunityApi.publishSticker(this, pack, sticker)
                    runOnUiThread {
                        if (result.isSuccess) {
                            toastLong("Sticker published to Community! 🌍")
                        } else {
                            toastLong("Community is not available right now")
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Share with Sound ----------------

    private fun shareWithSound(sticker: StickerItem) {
        Toast.makeText(this, "Making video with sound…", Toast.LENGTH_SHORT).show()
        executor.execute {
            val dir = PackStore.packDir(this, pack.id)
            val bitmap = BitmapFactory.decodeFile(File(dir, sticker.imageFile).absolutePath)
            if (bitmap == null) {
                runOnUiThread {
                    Toast.makeText(this, "Could not read sticker image", Toast.LENGTH_SHORT).show()
                }
                return@execute
            }
            val audio = sticker.audioFile?.let { File(dir, it) }
            val workDir = File(cacheDir, "shared").apply { mkdirs() }
            val result = VideoMaker.makeShareVideo(workDir, bitmap, audio)
            runOnUiThread {
                if (!result.hasAudio) {
                    Toast.makeText(
                        this,
                        "Sharing as a silent video (sound could not be added).",
                        Toast.LENGTH_LONG
                    ).show()
                }
                shareVideo(result.file)
            }
        }
    }

    private fun shareVideo(file: File) {
        val uri = FileProvider.getUriForFile(this, "com.foriad.soundstick.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Made with SoundStick")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Share sticker with sound"))
        } catch (e: Exception) {
            Toast.makeText(this, "No app can share this video", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Grid ----------------

    private inner class StickerAdapter : RecyclerView.Adapter<StickerAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val itemBinding = ItemStickerBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(itemBinding)
        }

        override fun getItemCount(): Int = pack.stickers.size

        override fun onViewRecycled(holder: Holder) {
            super.onViewRecycled(holder)
            holder.stopAnimation()
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val sticker = pack.stickers[position]
            holder.stopAnimation()
            holder.boundStickerId = sticker.id
            val dir = PackStore.packDir(this@PackActivity, pack.id)
            val imgFile = File(dir, sticker.imageFile)
            holder.binding.txtAnimated.visibility =
                if (sticker.animated) View.VISIBLE else View.GONE
            if (imgFile.exists()) {
                holder.binding.imgSticker.setImageBitmap(
                    BitmapFactory.decodeFile(imgFile.absolutePath)
                )
            }
            if (sticker.animated && imgFile.exists()) {
                startGridAnimation(holder, sticker.id, imgFile)
            }
            holder.binding.imgSticker.setOnClickListener { view ->
                // Bounce/pulse the sticker while its sound plays.
                view.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).withEndAction {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                }.start()
                val audio = sticker.audioFile?.let { File(dir, it) }
                if (audio != null && audio.exists()) {
                    SoundPlayer.play(audio)
                } else {
                    Toast.makeText(
                        this@PackActivity, "This sticker has no sound",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            holder.binding.btnShareSound.setOnClickListener { shareWithSound(sticker) }
            holder.binding.btnPublishSticker.setOnClickListener { confirmPublishSticker(sticker) }
        }

        /** Decodes an animated WebP's frames and cycles them in the cell. */
        private fun startGridAnimation(holder: Holder, stickerId: String, file: File) {
            executor.execute {
                val (frames, durationMs) = AnimatedWebPReader.decodeFrames(file)
                if (frames.size < 2) return@execute
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (holder.boundStickerId != stickerId) return@runOnUiThread
                    holder.playFrames(frames, durationMs)
                }
            }
        }

        inner class Holder(val binding: ItemStickerBinding) :
            RecyclerView.ViewHolder(binding.root) {

            var boundStickerId: String? = null
            private val animHandler = Handler(Looper.getMainLooper())
            private var animRunnable: Runnable? = null

            fun playFrames(frames: List<Bitmap>, durationMs: Int) {
                stopAnimation()
                var index = 0
                binding.imgSticker.setImageBitmap(frames[0])
                val runnable = object : Runnable {
                    override fun run() {
                        index = (index + 1) % frames.size
                        binding.imgSticker.setImageBitmap(frames[index])
                        animHandler.postDelayed(this, durationMs.coerceIn(40, 1000).toLong())
                    }
                }
                animRunnable = runnable
                animHandler.postDelayed(runnable, durationMs.coerceIn(40, 1000).toLong())
            }

            fun stopAnimation() {
                animRunnable?.let { animHandler.removeCallbacks(it) }
                animRunnable = null
            }
        }
    }
}
