package com.foriad.soundstick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** One sticker: a 512x512 WebP image (static or animated) plus an optional sound file. */
data class StickerItem(
    val id: String,
    val imageFile: String,
    val audioFile: String?,
    val animated: Boolean = false
)

/** A named sticker pack stored in app-private storage. */
data class StickerPack(
    val id: String,
    val name: String,
    val publisher: String = "SoundStick",
    val stickers: MutableList<StickerItem> = mutableListOf()
) {
    /**
     * WhatsApp packs must be all-static or all-animated. A pack's type is
     * locked by its first sticker; an empty pack has no type yet.
     */
    val isAnimated: Boolean
        get() = stickers.firstOrNull()?.animated ?: false

    val hasMixedTypes: Boolean
        get() = stickers.distinctBy { it.animated }.size > 1

    /** True if a sticker of the given type may be added to this pack. */
    fun accepts(animated: Boolean): Boolean =
        stickers.isEmpty() || (isAnimated == animated && !hasMixedTypes)
}

/**
 * Stores packs as JSON (packs.json) plus files under
 * filesDir/packs/<packId>/ in app-private storage.
 *
 * WhatsApp size rules are enforced here, at save time:
 *  - static sticker: exactly 512x512 WebP, <= 100 KB
 *  - animated sticker: exactly 512x512 WebP, <= 500 KB
 *  - tray icon: exactly 96x96 PNG, <= 50 KB
 */
object PackStore {
    private const val INDEX_FILE = "packs.json"
    const val TRAY_FILE = "tray.png"

    const val STATIC_MAX_BYTES = 100_000
    const val ANIMATED_MAX_BYTES = 500_000
    const val TRAY_MAX_BYTES = 50_000
    /** Encode targets kept just under the hard limits for safety. */
    private const val STATIC_TARGET_BYTES = 98_000
    private const val TRAY_TARGET_BYTES = 49_000

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
                                .ifEmpty { null },
                            // Backward compatible: packs.json written by v1.0.0
                            // has no "animated" key -> defaults to false.
                            animated = s.optBoolean("animated", false)
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
                so.put("animated", s.animated)
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

    private fun webpFormat(): Bitmap.CompressFormat =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }

    private fun encodeWebP(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(webpFormat(), quality, out)
        return out.toByteArray()
    }

    /**
     * Encodes a static sticker so the result is <= 100 KB: starts at
     * quality 82 and re-encodes lower while the file is too big
     * (below quality 30 it keeps stepping down to 10 for very
     * detailed photos, so the stored file always fits WhatsApp's limit).
     */
    fun encodeStaticSticker(bitmap512: Bitmap): ByteArray {
        var quality = 82
        var bytes = encodeWebP(bitmap512, quality)
        while (bytes.size > STATIC_TARGET_BYTES && quality > 10) {
            quality = if (quality > 30) quality - 10 else quality - 5
            bytes = encodeWebP(bitmap512, quality)
        }
        return bytes
    }

    /**
     * Adds a static sticker (bitmap re-encoded to a size-safe 512x512 WebP
     * + copied audio file) to [pack] and refreshes the pack tray icon.
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
        File(dir, imageName).writeBytes(encodeStaticSticker(bitmap512))
        val audioName = copyAudio(dir, stickerId, audioSource)
        val item = StickerItem(stickerId, imageName, audioName, animated = false)
        appendItem(context, pack, item)
        return item
    }

    /**
     * Adds an animated sticker: the already-encoded animated WebP bytes are
     * stored exactly as given (never re-encoded from a bitmap, which would
     * flatten the animation). [firstFrame] is used for the tray icon.
     */
    fun addAnimatedSticker(
        context: Context,
        pack: StickerPack,
        webpBytes: ByteArray,
        audioSource: File?,
        firstFrame: Bitmap?
    ): StickerItem {
        val dir = packDir(context, pack.id)
        val stickerId = UUID.randomUUID().toString()
        val imageName = "$stickerId.webp"
        File(dir, imageName).writeBytes(webpBytes)
        val audioName = copyAudio(dir, stickerId, audioSource)
        val item = StickerItem(stickerId, imageName, audioName, animated = true)
        val packs = loadPacks(context)
        val target = packs.firstOrNull { it.id == pack.id } ?: pack
        target.stickers.add(item)
        savePacks(context, packs)
        if (firstFrame != null) writeTrayIcon(dir, firstFrame) else refreshTrayIcon(context, target)
        return item
    }

    private fun appendItem(context: Context, pack: StickerPack, item: StickerItem) {
        val packs = loadPacks(context)
        val target = packs.firstOrNull { it.id == pack.id } ?: pack
        target.stickers.add(item)
        savePacks(context, packs)
        refreshTrayIcon(context, target)
    }

    private fun copyAudio(dir: File, stickerId: String, audioSource: File?): String? {
        if (audioSource == null || !audioSource.exists()) return null
        val ext = audioSource.extension.ifEmpty { "m4a" }
        val audioName = "$stickerId.$ext"
        audioSource.copyTo(File(dir, audioName), overwrite = true)
        return audioName
    }

    /** Tray icon = first sticker scaled to 96x96 PNG (WhatsApp requirement). */
    fun refreshTrayIcon(context: Context, pack: StickerPack) {
        val first = pack.stickers.firstOrNull() ?: return
        val dir = packDir(context, pack.id)
        // BitmapFactory decodes the first frame of an animated WebP, which is
        // exactly what a static tray icon needs.
        val img = BitmapFactory.decodeFile(File(dir, first.imageFile).absolutePath) ?: return
        writeTrayIcon(dir, img)
    }

    /**
     * Writes tray.png at exactly 96x96 and <= 50 KB. PNG is lossless, so if
     * a detailed photo does not fit we re-encode from an RGB_565 copy and
     * then progressively posterize the colours until it fits.
     */
    private fun writeTrayIcon(dir: File, source: Bitmap) {
        val trayFile = File(dir, TRAY_FILE)
        val scaled = Bitmap.createScaledBitmap(source, 96, 96, true)
        var bytes = encodePng(scaled)
        if (bytes.size > TRAY_TARGET_BYTES) {
            val rgb565 = scaled.copy(Bitmap.Config.RGB_565, false)
            if (rgb565 != null) {
                val b565 = encodePng(rgb565)
                if (b565.size < bytes.size) bytes = b565
            }
        }
        var bits = 6
        while (bytes.size > TRAY_TARGET_BYTES && bits >= 2) {
            val b = encodePng(posterize(scaled, bits))
            if (b.size < bytes.size) bytes = b
            bits -= 2
        }
        trayFile.writeBytes(bytes)
    }

    private fun encodePng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** Reduces each colour channel to [bits] bits to shrink the PNG. */
    private fun posterize(source: Bitmap, bits: Int): Bitmap {
        val src = if (source.width == 96 && source.height == 96) source
        else Bitmap.createScaledBitmap(source, 96, 96, true)
        val out = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val shift = 8 - bits
        val pixels = IntArray(96 * 96)
        src.getPixels(pixels, 0, 96, 0, 0, 96, 96)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (Color.red(c) shr shift) shl shift
            val g = (Color.green(c) shr shift) shl shift
            val b = (Color.blue(c) shr shift) shl shift
            pixels[i] = Color.argb(Color.alpha(c), r, g, b)
        }
        out.setPixels(pixels, 0, 96, 0, 0, 96, 96)
        return out
    }

    /**
     * Repairs a pack saved before the WhatsApp size rules were enforced:
     * re-encodes any oversized static sticker from its stored image and
     * rewrites the tray icon within its 50 KB limit. Call off the UI thread.
     */
    fun healPack(context: Context, pack: StickerPack) {
        val dir = packDir(context, pack.id)
        for (sticker in pack.stickers) {
            if (sticker.animated) continue
            val file = File(dir, sticker.imageFile)
            if (!file.exists() || file.length() <= STATIC_MAX_BYTES) continue
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
            if (bitmap.width != 512 || bitmap.height != 512) continue
            val bytes = encodeStaticSticker(bitmap)
            if (bytes.size < file.length()) file.writeBytes(bytes)
        }
        refreshTrayIcon(context, pack)
    }

    /** Decodes just the bounds of an image file, or null if unreadable. */
    fun imageBounds(file: File): Pair<Int, Int>? {        if (!file.exists()) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }
}

/** Bitmap helpers for turning arbitrary images into sticker bitmaps. */
object Bitmap512 {
    /** Center-crops [source] to a square and scales it to exactly 512x512. */
    fun centerSquare(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val x = (source.width - size) / 2
        val y = (source.height - size) / 2
        val cropped = Bitmap.createBitmap(source, x, y, size, size)
        return if (cropped.width == 512 && cropped.height == 512) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, 512, 512, true)
        }
    }
}
