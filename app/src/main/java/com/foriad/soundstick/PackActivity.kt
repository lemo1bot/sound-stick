package com.foriad.soundstick

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
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

        binding.txtPackTitle.text = pack.name
        binding.txtPackInfo.text =
            "${pack.stickers.size} stickers · by ${pack.publisher}"

        binding.recyclerStickers.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerStickers.adapter = StickerAdapter()

        binding.btnAddWhatsApp.setOnClickListener { addToWhatsApp() }

        try {
            binding.adView.adUnitId = AdsConfig.BANNER_ID
            binding.adView.loadAd(AdRequest.Builder().build())
        } catch (e: Exception) {
            binding.adView.visibility = android.view.View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SoundPlayer.stop()
        executor.shutdownNow()
    }

    /** Official WhatsApp sticker-pack hand-off intent. */
    private fun addToWhatsApp() {
        if (pack.stickers.size < 3) {
            Toast.makeText(
                this,
                "WhatsApp needs at least 3 stickers in a pack. This pack has ${pack.stickers.size}.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (pack.stickers.size > 30) {
            Toast.makeText(
                this,
                "WhatsApp allows at most 30 stickers per pack.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val intent = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", pack.id)
            putExtra("sticker_pack_authority", StickerContentProvider.AUTHORITY)
            putExtra("sticker_pack_name", pack.name)
        }
        try {
            startActivityForResult(intent, REQUEST_ADD_TO_WHATSAPP)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "WhatsApp is not installed on this phone.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ADD_TO_WHATSAPP) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Pack added to WhatsApp!", Toast.LENGTH_SHORT).show()
            } else if (data != null) {
                val reason = data.getStringExtra("validation_error")
                if (!reason.isNullOrEmpty()) {
                    Toast.makeText(
                        this, "WhatsApp could not add this pack: $reason",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

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

    private inner class StickerAdapter : RecyclerView.Adapter<StickerAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val itemBinding = ItemStickerBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(itemBinding)
        }

        override fun getItemCount(): Int = pack.stickers.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val sticker = pack.stickers[position]
            val dir = PackStore.packDir(this@PackActivity, pack.id)
            val imgFile = File(dir, sticker.imageFile)
            if (imgFile.exists()) {
                holder.binding.imgSticker.setImageBitmap(
                    BitmapFactory.decodeFile(imgFile.absolutePath)
                )
            }
            holder.binding.imgSticker.setOnClickListener { view ->
                view.startAnimation(
                    AnimationUtils.loadAnimation(this@PackActivity, android.R.anim.fade_in)
                )
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
        }

        inner class Holder(val binding: ItemStickerBinding) :
            RecyclerView.ViewHolder(binding.root)
    }
}
