package com.foriad.soundstick

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * WhatsApp sticker pack content provider, aligned with the CURRENT official
 * sample (github.com/WhatsApp/stickers, Android StickerContentProvider):
 *
 *  - content://<authority>/metadata           -> one row per pack, 13 columns:
 *    sticker_pack_identifier, sticker_pack_name, sticker_pack_publisher,
 *    sticker_pack_icon, android_play_store_link, ios_app_download_link,
 *    sticker_pack_publisher_email, sticker_pack_publisher_website,
 *    sticker_pack_privacy_policy_website,
 *    sticker_pack_license_agreement_website, image_data_version,
 *    whatsapp_will_not_cache_stickers, animated_sticker_pack
 *  - content://<authority>/metadata/<packId>  -> single pack row
 *  - content://<authority>/stickers/<packId>  -> rows of:
 *    sticker_file_name, sticker_emoji, sticker_accessibility_text
 *  - openFile / openAssetFile on stickers_asset/<packId>/<file>
 *    -> tray PNG / sticker WebP bytes
 *
 * Differences from the previous version of this provider (which WhatsApp
 * rejected with a validation_error): the metadata cursor previously had
 * only 10 columns, used the non-existent column name "ios_app_store_link",
 * and had no image_data_version / avoid-cache / animated_sticker_pack
 * columns; the stickers cursor lacked sticker_accessibility_text.
 *
 * Note: official third-party WhatsApp sticker packs carry no sound, so the
 * stickers served here are the pack's WebP images (sound lives in the app
 * and in "Share with Sound" videos).
 */
class StickerContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.foriad.soundstick.stickercontentprovider"

        /** Where users can get SoundStick (we distribute via GitHub). */
        const val ANDROID_PLAY_STORE_LINK = "https://github.com/lemo1bot/sound-stick"
        const val DEFAULT_EMOJI = "😂"

        private const val METADATA = 1
        private const val METADATA_SINGLE = 2
        private const val STICKERS = 3
        private const val STICKERS_ASSET = 4

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "metadata", METADATA)
            addURI(AUTHORITY, "metadata/*", METADATA_SINGLE)
            addURI(AUTHORITY, "stickers/*", STICKERS)
            addURI(AUTHORITY, "stickers_asset/*/*", STICKERS_ASSET)
        }

        // Column names and order exactly as in WhatsApp's current sample.
        private val METADATA_COLUMNS = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "android_play_store_link",
            "ios_app_download_link",
            "sticker_pack_publisher_email",
            "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website",
            "image_data_version",
            "whatsapp_will_not_cache_stickers",
            "animated_sticker_pack"
        )

        private val STICKER_COLUMNS = arrayOf(
            "sticker_file_name",
            "sticker_emoji",
            "sticker_accessibility_text"
        )
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        return when (uriMatcher.match(uri)) {
            METADATA -> metadataCursor(ctx, PackStore.loadPacks(ctx), uri)
            METADATA_SINGLE -> {
                val packId = uri.lastPathSegment ?: return null
                val pack = PackStore.getPack(ctx, packId)
                metadataCursor(ctx, listOfNotNull(pack), uri)
            }
            STICKERS -> {
                val packId = uri.lastPathSegment ?: return null
                val pack = PackStore.getPack(ctx, packId) ?: return null
                val cursor = MatrixCursor(STICKER_COLUMNS)
                for (sticker in pack.stickers) {
                    cursor.addRow(arrayOf(sticker.imageFile, DEFAULT_EMOJI, ""))
                }
                cursor.setNotificationUri(ctx.contentResolver, uri)
                cursor
            }
            else -> null
        }
    }

    private fun metadataCursor(
        ctx: android.content.Context,
        packs: List<StickerPack>,
        uri: Uri
    ): MatrixCursor {
        val cursor = MatrixCursor(METADATA_COLUMNS)
        for (pack in packs) {
            if (pack.stickers.size < 3) continue
            cursor.addRow(
                arrayOf(
                    pack.id,
                    pack.name,
                    pack.publisher,
                    PackStore.TRAY_FILE,
                    ANDROID_PLAY_STORE_LINK,
                    "",
                    "",
                    "",
                    "",
                    "",
                    imageDataVersion(pack),
                    0, // whatsapp_will_not_cache_stickers = false
                    if (pack.isAnimated) 1 else 0
                )
            )
        }
        cursor.setNotificationUri(ctx.contentResolver, uri)
        return cursor
    }

    /**
     * A version string that changes whenever the pack's stickers change, so
     * WhatsApp knows to refresh a previously added pack.
     */
    private fun imageDataVersion(pack: StickerPack): String {
        var hash = pack.stickers.size
        for (s in pack.stickers) {
            hash = 31 * hash + s.id.hashCode()
            hash = 31 * hash + s.imageFile.hashCode()
        }
        return (hash and Int.MAX_VALUE).toString()
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolveAssetFile(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val file = resolveAssetFile(uri)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    private fun resolveAssetFile(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("No context")
        if (uriMatcher.match(uri) != STICKERS_ASSET) {
            throw FileNotFoundException("Unknown uri: $uri")
        }
        val segments = uri.pathSegments
        // stickers_asset/<packId>/<fileName>
        val packId = segments[segments.size - 2]
        val fileName = segments[segments.size - 1]
        if (fileName.contains("..") || packId.contains("..") ||
            fileName.contains("/") || packId.contains("/")
        ) {
            throw FileNotFoundException("Bad path")
        }
        // Only serve files that belong to the pack (tray or a sticker).
        val pack = PackStore.getPack(ctx, packId)
            ?: throw FileNotFoundException("Unknown pack: $packId")
        if (fileName != PackStore.TRAY_FILE && pack.stickers.none { it.imageFile == fileName }) {
            throw FileNotFoundException("Unknown file: $fileName")
        }
        val file = File(PackStore.packDir(ctx, packId), fileName)
        if (!file.exists()) throw FileNotFoundException(file.absolutePath)
        return file
    }

    override fun getType(uri: Uri): String {
        return when (uriMatcher.match(uri)) {
            METADATA -> "vnd.android.cursor.dir/vnd.$AUTHORITY.metadata"
            METADATA_SINGLE -> "vnd.android.cursor.item/vnd.$AUTHORITY.metadata"
            STICKERS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.stickers"
            STICKERS_ASSET -> {
                val name = uri.lastPathSegment ?: ""
                if (name == PackStore.TRAY_FILE || name.endsWith(".png")) {
                    "image/png"
                } else {
                    "image/webp"
                }
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Not supported")

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Not supported")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Not supported")
}
