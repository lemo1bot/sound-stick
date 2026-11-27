package com.foriad.soundstick

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.io.File

/**
 * Shared "Add to WhatsApp / WhatsApp Business" flow.
 *
 * Before the hand-off intent is fired we validate the pack against
 * WhatsApp's documented rules, so problems are reported in plain language
 * here instead of WhatsApp failing with an opaque validation_error:
 *  - 3..30 stickers
 *  - all stickers the same type (all static or all animated)
 *  - every sticker file exists, is exactly 512x512, and is within the
 *    size limit (100 KB static / 500 KB animated, frames >= 8 ms,
 *    total animation <= 10 s)
 *  - tray icon exists, is exactly 96x96 and <= 50 KB
 */
object WhatsAppHelper {

    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"

    fun displayName(packageName: String): String =
        if (packageName == WHATSAPP_BUSINESS_PACKAGE) "WhatsApp Business" else "WhatsApp"

    fun isInstalled(context: Context, packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /** Returns a user-readable problem, or null if the pack is valid. */
    fun validatePack(context: Context, pack: StickerPack): String? {
        if (pack.stickers.size < 3) {
            return "WhatsApp needs at least 3 stickers in a pack. " +
                "“${pack.name}” has ${pack.stickers.size} — add more stickers first."
        }
        if (pack.stickers.size > 30) {
            return "WhatsApp allows at most 30 stickers per pack. " +
                "“${pack.name}” has ${pack.stickers.size}."
        }
        if (pack.hasMixedTypes) {
            return "“${pack.name}” mixes static and animated stickers. " +
                "WhatsApp packs must be all one type — split it into two packs."
        }
        val dir = PackStore.packDir(context, pack.id)
        for ((index, sticker) in pack.stickers.withIndex()) {
            val label = "Sticker ${index + 1} in “${pack.name}”"
            val file = File(dir, sticker.imageFile)
            if (!file.exists()) return "$label is missing its image file."
            val bounds = PackStore.imageBounds(file)
                ?: return "$label has an unreadable image file."
            if (bounds.first != 512 || bounds.second != 512) {
                return "$label is ${bounds.first}x${bounds.second}, but WhatsApp requires exactly 512x512."
            }
            if (sticker.animated) {
                if (file.length() > PackStore.ANIMATED_MAX_BYTES) {
                    return "$label is too big for WhatsApp (animated limit 500 KB)."
                }
                val info = AnimatedWebPReader.parseInfo(file)
                    ?: return "$label is not a valid animated WebP."
                if (info.frameDurationsMs.any { it < 8 }) {
                    return "$label has animation frames shorter than 8 ms, which WhatsApp rejects."
                }
                if (info.totalDurationMs > 10_000) {
                    return "$label is longer than WhatsApp's 10-second animation limit."
                }
            } else {
                if (file.length() > PackStore.STATIC_MAX_BYTES) {
                    return "$label is too big for WhatsApp (static limit 100 KB)."
                }
            }
        }
        val tray = File(dir, PackStore.TRAY_FILE)
        if (!tray.exists()) return "“${pack.name}” is missing its tray icon."
        val trayBounds = PackStore.imageBounds(tray)
            ?: return "“${pack.name}” has an unreadable tray icon."
        if (trayBounds.first != 96 || trayBounds.second != 96) {
            return "The tray icon of “${pack.name}” must be exactly 96x96."
        }
        if (tray.length() > PackStore.TRAY_MAX_BYTES) {
            return "The tray icon of “${pack.name}” is too big for WhatsApp (limit 50 KB)."
        }
        return null
    }

    /** Builds the official ENABLE_STICKER_PACK intent targeted at one app. */
    fun buildIntent(pack: StickerPack, packageName: String): Intent =
        Intent(ACTION_ENABLE_STICKER_PACK).apply {
            setPackage(packageName)
            putExtra("sticker_pack_id", pack.id)
            putExtra("sticker_pack_authority", StickerContentProvider.AUTHORITY)
            putExtra("sticker_pack_name", pack.name)
        }

    /**
     * Turns an onActivityResult from the add flow into a user message.
     * Returns null when there is nothing worth reporting.
     */
    fun resultMessage(resultCode: Int, data: Intent?, packageName: String): String? {
        val app = displayName(packageName)
        if (resultCode == android.app.Activity.RESULT_OK) {
            return "Pack added to $app!"
        }
        val reason = data?.getStringExtra("validation_error")
        return if (!reason.isNullOrEmpty()) {
            "$app could not add this pack: $reason"
        } else {
            null
        }
    }
}
