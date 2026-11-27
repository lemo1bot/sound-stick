package com.foriad.soundstick

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.foriad.soundstick.databinding.ActivityCommunityBinding
import com.foriad.soundstick.databinding.ItemCommunityBinding
import java.io.File
import java.util.concurrent.Executors

/**
 * Community feed: stickers other SoundStick users published.
 * Tap a sticker to hear its sound; "Use this sticker" copies it into a
 * local "Community" pack so it can be added to WhatsApp like any pack.
 * Any backend problem shows "Community is not available right now".
 */
class CommunityActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCommunityBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val adapter = CommunityAdapter()
    private var items: List<CommunitySticker> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommunityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerCommunity.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerCommunity.adapter = adapter

        loadFeed()
    }

    override fun onDestroy() {
        super.onDestroy()
        SoundPlayer.stop()
        executor.shutdownNow()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun loadFeed() {
        if (!CommunityConfig.isConfigured) {
            binding.txtCommunityStatus.text = "Community is not available right now"
            return
        }
        binding.txtCommunityStatus.text = "Loading…"
        executor.execute {
            val result = CommunityApi.fetchFeed()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { feed ->
                        items = feed
                        adapter.notifyDataSetChanged()
                        binding.txtCommunityStatus.text = when {
                            feed.isEmpty() ->
                                "No community stickers yet. Publish one of yours from a pack!"
                            else -> ""
                        }
                    },
                    onFailure = {
                        binding.txtCommunityStatus.text =
                            "Community is not available right now"
                    }
                )
            }
        }
    }

    /** Tap: download the sticker's sound (if any) and play it, image pulsing. */
    private fun playSticker(item: CommunitySticker, imageView: View) {
        val audioUrl = item.audioUrl
        if (audioUrl.isNullOrEmpty()) {
            toast("This sticker has no sound")
            return
        }
        imageView.animate().scaleX(1.12f).scaleY(1.12f).setDuration(130).withEndAction {
            imageView.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        }.start()
        executor.execute {
            val bytes = CommunityApi.downloadBytes(audioUrl).getOrNull()
            if (bytes == null) {
                runOnUiThread { toast("Could not download that sound") }
                return@execute
            }
            val ext = audioUrl.substringAfterLast('.', "m4a").substringBefore('?')
                .lowercase().ifEmpty { "m4a" }
            val file = File(cacheDir, "community_sound.$ext")
            try {
                file.writeBytes(bytes)
            } catch (e: Exception) {
                runOnUiThread { toast("Could not play that sound") }
                return@execute
            }
            runOnUiThread { SoundPlayer.play(file) }
        }
    }

    /** Downloads image (+ audio) into a local Community pack of matching type. */
    private fun useSticker(item: CommunitySticker) {
        toast("Adding to your Community pack…")
        executor.execute {
            val imageBytes = CommunityApi.downloadBytes(item.imageUrl).getOrNull()
            if (imageBytes == null) {
                runOnUiThread { toast("Could not download that sticker") }
                return@execute
            }
            var audioTemp: File? = null
            if (!item.audioUrl.isNullOrEmpty()) {
                val audioBytes = CommunityApi.downloadBytes(item.audioUrl).getOrNull()
                if (audioBytes != null) {
                    val ext = item.audioUrl.substringAfterLast('.', "m4a")
                        .substringBefore('?').lowercase().ifEmpty { "m4a" }
                    audioTemp = File(cacheDir, "community_use.$ext")
                    try {
                        audioTemp.writeBytes(audioBytes)
                    } catch (e: Exception) {
                        audioTemp = null
                    }
                }
            }
            val pack = findOrCreateCommunityPack(item.animated)
            if (item.animated) {
                // Keep the animated bytes exactly as published.
                val firstFrame = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                PackStore.addAnimatedSticker(this, pack, imageBytes, audioTemp, firstFrame)
            } else {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                if (bitmap == null) {
                    runOnUiThread { toast("That sticker image could not be read") }
                    return@execute
                }
                val square = Bitmap512.centerSquare(bitmap)
                PackStore.addSticker(this, pack, square, audioTemp)
            }
            runOnUiThread {
                toast("Saved to pack “${pack.name}” — open it to Add to WhatsApp")
            }
        }
    }

    /**
     * Finds a "Community" pack that accepts this sticker type (WhatsApp
     * packs are all-static or all-animated), or creates a new one.
     */
    private fun findOrCreateCommunityPack(animated: Boolean): StickerPack {
        val packs = PackStore.loadPacks(this)
        val existing = packs.firstOrNull {
            it.name.startsWith("Community") && it.accepts(animated)
        }
        if (existing != null) return existing
        return PackStore.createPack(this, "Community")
    }

    private inner class CommunityAdapter :
        RecyclerView.Adapter<CommunityAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val itemBinding = ItemCommunityBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(itemBinding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.binding.txtCommunityName.text = item.name
            holder.binding.txtCommunityPublisher.text = "by ${item.publisher}"
            holder.binding.txtCommunityAnimated.visibility =
                if (item.animated) View.VISIBLE else View.GONE
            holder.binding.imgCommunity.setImageResource(R.drawable.ic_launcher_logo)
            CommunityImageLoader.load(item.imageUrl, holder.binding.imgCommunity)
            holder.binding.imgCommunity.setOnClickListener {
                playSticker(item, holder.binding.imgCommunity)
            }
            holder.binding.btnUseSticker.setOnClickListener { useSticker(item) }
        }

        inner class Holder(val binding: ItemCommunityBinding) :
            RecyclerView.ViewHolder(binding.root)
    }
}
