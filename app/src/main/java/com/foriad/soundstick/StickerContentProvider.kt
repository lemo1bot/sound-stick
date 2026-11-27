package com.foriad.soundstick

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * WhatsApp sticker pack content provider, following the official
 * WAStickerApps sample contract.
 *
 * WhatsApp reads:
 *  - content://<authority>/metadata            -> one row per pack
 *  - content://<authority>/stickers/<packId>   -> sticker file names
 *  - openFile on stickers_asset/<packId>/<file> -> tray PNG / sticker WebP
 *
 * Note: official third-party WhatsApp sticker packs are image-only, so the
 * stickers served here are silent WebP images (sound lives in the app and
 * in "Share with Sound" videos).
 */
class StickerContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.foriad.soundstick.stickercontentprovider"
        private const val METADATA = 1
        private const val STICKERS = 2
        private const val STICKERS_ASSET = 3

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "metadata", METADATA)
            addURI(AUTHORITY, "stickers/*", STICKERS)
            addURI(AUTHORITY, "stickers_asset/*/*", STICKERS_ASSET)
        }

        private val METADATA_COLUMNS = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "android_play_store_link",
            "ios_app_store_link",
            "sticker_pack_publisher_email",
            "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website"
        )

        private val STICKER_COLUMNS = arrayOf(
            "sticker_file_name",
            "sticker_emoji"
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
            METADATA -> {
                val cursor = MatrixCursor(METADATA_COLUMNS)
                for (pack in PackStore.loadPacks(ctx)) {
                    if (pack.stickers.size < 3) continue
                    cursor.addRow(
                        arrayOf(
                            pack.id,
                            pack.name,
                            pack.publisher,
                            PackStore.TRAY_FILE,
                            "",
                            "",
                            "",
                            "",
                            "",
                            ""
                        )
                    )
                }
                cursor
            }
            STICKERS -> {
                val packId = uri.lastPathSegment ?: return null
                val pack = PackStore.getPack(ctx, packId) ?: return null
                val cursor = MatrixCursor(STICKER_COLUMNS)
                for (sticker in pack.stickers) {
                    cursor.addRow(arrayOf(sticker.imageFile, "🔊"))
                }
                cursor
            }
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("No context")
        if (uriMatcher.match(uri) != STICKERS_ASSET) {
            throw FileNotFoundException("Unknown uri: $uri")
        }
        val segments = uri.pathSegments
        // stickers_asset/<packId>/<fileName>
        val packId = segments[segments.size - 2]
        val fileName = segments[segments.size - 1]
        if (fileName.contains("..") || packId.contains("..")) {
            throw FileNotFoundException("Bad path")
        }
        val file = File(PackStore.packDir(ctx, packId), fileName)
        if (!file.exists()) throw FileNotFoundException(file.absolutePath)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        return when (uriMatcher.match(uri)) {
            METADATA -> "vnd.android.cursor.dir/vnd.$AUTHORITY.metadata"
            STICKERS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.stickers"
            STICKERS_ASSET -> {
                val name = uri.lastPathSegment ?: ""
                if (name.endsWith(".png")) "image/png" else "image/webp"
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
