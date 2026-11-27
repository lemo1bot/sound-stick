package com.foriad.soundstick

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.foriad.soundstick.databinding.ActivityMainBinding
import com.foriad.soundstick.databinding.ItemPackBinding
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = PackAdapter { pack ->
        startActivity(
            Intent(this, PackActivity::class.java)
                .putExtra(PackActivity.EXTRA_PACK_ID, pack.id)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MobileAds.initialize(this) {}
        InterstitialHelper.init(this)
        loadBanner()

        binding.recyclerPacks.layoutManager = LinearLayoutManager(this)
        binding.recyclerPacks.adapter = adapter

        binding.btnCreate.setOnClickListener {
            startActivity(Intent(this, CreateStickerActivity::class.java))
        }
        binding.btnFollowX.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/Bank_of_eth")))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open X", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val packs = PackStore.loadPacks(this)
        adapter.submit(packs)
        binding.txtEmpty.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadBanner() {
        try {
            binding.adView.adUnitId = AdsConfig.BANNER_ID
            binding.adView.loadAd(AdRequest.Builder().build())
        } catch (e: Exception) {
            binding.adView.visibility = View.GONE
        }
    }

    private inner class PackAdapter(
        private val onClick: (StickerPack) -> Unit
    ) : RecyclerView.Adapter<PackAdapter.Holder>() {
        private var packs: List<StickerPack> = emptyList()

        fun submit(list: List<StickerPack>) {
            packs = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val itemBinding = ItemPackBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(itemBinding)
        }

        override fun getItemCount(): Int = packs.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val pack = packs[position]
            holder.binding.txtPackName.text = pack.name
            holder.binding.txtPackCount.text =
                "${pack.stickers.size} sticker${if (pack.stickers.size == 1) "" else "s"}"
            val first = pack.stickers.firstOrNull()
            if (first != null) {
                val file = File(PackStore.packDir(this@MainActivity, pack.id), first.imageFile)
                if (file.exists()) {
                    holder.binding.imgPackIcon.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                } else {
                    holder.binding.imgPackIcon.setImageResource(R.drawable.logo_header)
                }
            } else {
                holder.binding.imgPackIcon.setImageResource(R.drawable.logo_header)
            }
            holder.itemView.setOnClickListener { onClick(pack) }
        }

        inner class Holder(val binding: ItemPackBinding) :
            RecyclerView.ViewHolder(binding.root)
    }
}
