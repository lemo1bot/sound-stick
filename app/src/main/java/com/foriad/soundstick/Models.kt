package com.foriad.soundstick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** One sticker: a 512x512 WebP image plus an optional sound file. */
data class StickerItem(
    val id: String,
    val imageFile: String,
    val audioFile: String?
)

/** A named sticker pack stored in app-private storage. */
data class StickerPack(
    val id: String,
    val name: String,
    val publisher: String = "SoundStick",
    val stickers: MutableList<StickerItem> = mutableListOf()
)

/**
 * Stores packs as JSON (packs.json) plus files under
 * filesDir/packs/<packId>/ in app-private storage.
 */
object PackStore {
    private const val INDEX_FILE = "packs.json"
    const val TRAY_FILE = "tray.png"

    fun packsDir(context: Context): File =
        File(context.filesDir, "packs").apply { mkdirs() }

    fun packDir(context: Context, packId: String): File =
        File(packsDir(context), packId).apply { mkdirs() }

    fun loadPacks(context: Context): MutableList<StickerPack> {
        val index = File(packsDir(context), INDEX_FILE)
        if (!index.exists()) return mutableListOf()
        return try {
            val root = JSONArray(index.readText())
            val packs = mutableListOf<StickerPack>()
            for (i in 0 until root.length()) {
                val obj = root.getJSONObject(i)
                val stickers = mutableListOf<StickerItem>()
                val arr = obj.optJSONArray("stickers") ?: JSONArray()
                for (j in 0 until arr.length()) {
                    val s = arr.getJSONObject(j)
                    stickers.add(
                        StickerItem(
                            id = s.getString("id"),
                            imageFile = s.getString("image"),
                            audioFile = if (s.isNull("audio")) null else s.optString("audio", "")
                                .ifEmpty { null }
                        )
                    )
                }
                packs.add(
                    StickerPack(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        publisher = obj.optString("publisher", "SoundStick"),
                        stickers = stickers
                    )
                )
            }
            packs
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun savePacks(context: Context, packs: List<StickerPack>) {
        val root = JSONArray()
        for (pack in packs) {
            val obj = JSONObject()
            obj.put("id", pack.id)
            obj.put("name", pack.name)
            obj.put("publisher", pack.publisher)
            val arr = JSONArray()
            for (s in pack.stickers) {
                val so = JSONObject()
                so.put("id", s.id)
                so.put("image", s.imageFile)
                if (s.audioFile != null) so.put("audio", s.audioFile) else so.put("audio", JSONObject.NULL)
                arr.put(so)
            }
            obj.put("stickers", arr)
            root.put(obj)
        }
        File(packsDir(context), INDEX_FILE).writeText(root.toString())
    }

    fun createPack(context: Context, name: String): StickerPack {
        val packs = loadPacks(context)
        val pack = StickerPack(id = UUID.randomUUID().toString(), name = name)
        packs.add(pack)
        packDir(context, pack.id)
        savePacks(context, packs)
        return pack
    }

    fun getPack(context: Context, packId: String): StickerPack? =
        loadPacks(context).firstOrNull { it.id == packId }

    /**
     * Adds a sticker (512x512 WebP + copied audio file) to [pack] and
     * refreshes the pack tray icon (96x96 PNG from the first sticker).
     */
    fun addSticker(
        context: Context,
        pack: StickerPack,
        bitmap512: Bitmap,
        audioSource: File?
    ): StickerItem {
        val dir = packDir(context, pack.id)
        val stickerId = UUID.randomUUID().toString()
        val imageName = "$stickerId.webp"
        val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        FileOutputStream(File(dir, imageName)).use { out ->
            bitmap512.compress(format, 82, out)
        }
        var audioName: String? = null
        if (audioSource != null && audioSource.exists()) {
            val ext = audioSource.extension.ifEmpty { "m4a" }
            audioName = "$stickerId.$ext"
            audioSource.copyTo(File(dir, audioName), overwrite = true)
        }
        val item = StickerItem(stickerId, imageName, audioName)
        val packs = loadPacks(context)
        val target = packs.firstOrNull { it.id == pack.id } ?: pack
        target.stickers.add(item)
        savePacks(context, packs)
        refreshTrayIcon(context, target)
        return item
    }

    /** Tray icon = first sticker scaled to 96x96 PNG (WhatsApp requirement). */
    fun refreshTrayIcon(context: Context, pack: StickerPack) {
        val first = pack.stickers.firstOrNull() ?: return
        val dir = packDir(context, pack.id)
        val img = BitmapFactory.decodeFile(File(dir, first.imageFile).absolutePath) ?: return
        val tray = Bitmap.createScaledBitmap(img, 96, 96, true)
        FileOutputStream(File(dir, TRAY_FILE)).use { out ->
            tray.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
