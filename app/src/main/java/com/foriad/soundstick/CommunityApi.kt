package com.foriad.soundstick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One row of the public community_stickers table. */
data class CommunitySticker(
    val id: String,
    val name: String,
    val publisher: String,
    val imageUrl: String,
    val audioUrl: String?,
    val animated: Boolean,
    val createdAt: String?
)

/**
 * Talks to the Supabase backend (REST + Storage public APIs) with the
 * anon key. All functions are synchronous and MUST be called off the UI
 * thread; every failure is reported as a failed Result, never a crash.
 */
object CommunityApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun baseRequest(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("apikey", CommunityConfig.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer ${CommunityConfig.SUPABASE_ANON_KEY}")

    /** GET the newest community stickers (up to 100). */
    fun fetchFeed(): Result<List<CommunitySticker>> {
        if (!CommunityConfig.isConfigured) {
            return Result.failure(IllegalStateException("Community is not configured"))
        }
        val url = "${CommunityConfig.SUPABASE_URL}/rest/v1/${CommunityConfig.TABLE}" +
            "?select=*&order=created_at.desc&limit=100"
        return try {
            client.newCall(baseRequest(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IllegalStateException("HTTP ${response.code}"))
                }
                val body = response.body?.string()
                    ?: return Result.failure(IllegalStateException("Empty response"))
                val rows = JSONArray(body)
                val out = ArrayList<CommunitySticker>(rows.length())
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val imageUrl = row.optString("image_url", "")
                    if (imageUrl.isEmpty()) continue
                    out.add(
                        CommunitySticker(
                            id = row.optString("id", UUID.randomUUID().toString()),
                            name = row.optString("name", "Sticker"),
                            publisher = row.optString("publisher", CommunityConfig.PUBLISHER_NAME),
                            imageUrl = imageUrl,
                            audioUrl = if (row.isNull("audio_url")) null
                            else row.optString("audio_url", "").ifEmpty { null },
                            animated = row.optBoolean("animated", false),
                            createdAt = if (row.isNull("created_at")) null
                            else row.optString("created_at", "").ifEmpty { null }
                        )
                    )
                }
                Result.success(out)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Downloads raw bytes (sticker image or audio). */
    fun downloadBytes(url: String): Result<ByteArray> {
        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IllegalStateException("HTTP ${response.code}"))
                }
                val bytes = response.body?.bytes()
                    ?: return Result.failure(IllegalStateException("Empty response"))
                Result.success(bytes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun publicUrl(fileName: String): String =
        "${CommunityConfig.SUPABASE_URL}/storage/v1/object/public/" +
            "${CommunityConfig.BUCKET}/$fileName"

    /** Uploads one object to the public stickers bucket. */
    private fun uploadObject(fileName: String, bytes: ByteArray, contentType: String): Result<String> {
        val url = "${CommunityConfig.SUPABASE_URL}/storage/v1/object/" +
            "${CommunityConfig.BUCKET}/$fileName"
        val request = baseRequest(url)
            .header("Content-Type", contentType)
            .header("x-upsert", "true")
            .post(bytes.toRequestBody(contentType.toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(publicUrl(fileName))
                } else {
                    Result.failure(IllegalStateException("Upload failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Publishes one local sticker: uploads its image (and audio, if any)
     * to Storage, then inserts the row into community_stickers.
     */
    fun publishSticker(context: Context, pack: StickerPack, sticker: StickerItem): Result<Unit> {
        if (!CommunityConfig.isConfigured) {
            return Result.failure(IllegalStateException("Community is not configured"))
        }
        val dir = PackStore.packDir(context, pack.id)
        val imageFile = File(dir, sticker.imageFile)
        if (!imageFile.exists()) {
            return Result.failure(IllegalStateException("Sticker image is missing"))
        }
        val uuid = UUID.randomUUID().toString()
        val imageUrl = uploadObject(
            "$uuid.webp", imageFile.readBytes(), "image/webp"
        ).getOrElse { return Result.failure(it) }

        var audioUrl: String? = null
        val audioFile = sticker.audioFile?.let { File(dir, it) }
        if (audioFile != null && audioFile.exists()) {
            val ext = audioFile.extension.ifEmpty { "m4a" }
            val contentType = when (ext.lowercase()) {
                "wav" -> "audio/wav"
                "mp3" -> "audio/mpeg"
                "ogg" -> "audio/ogg"
                else -> "audio/mp4"
            }
            audioUrl = uploadObject("$uuid.$ext", audioFile.readBytes(), contentType)
                .getOrElse { return Result.failure(it) }
        }

        val row = JSONObject().apply {
            put("name", pack.name)
            put("publisher", CommunityConfig.PUBLISHER_NAME)
            put("image_url", imageUrl)
            if (audioUrl != null) put("audio_url", audioUrl) else put("audio_url", JSONObject.NULL)
            put("animated", sticker.animated)
        }
        val url = "${CommunityConfig.SUPABASE_URL}/rest/v1/${CommunityConfig.TABLE}"
        val request = baseRequest(url)
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .post(row.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Publish failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Tiny in-house image loader with an LruCache — just enough for the
 * Community grid, so no image-loading library is needed.
 */
object CommunityImageLoader {
    private val cache = LruCache<String, Bitmap>(48)
    private val executor = Executors.newFixedThreadPool(3)

    fun load(url: String, imageView: ImageView) {
        imageView.tag = url
        val cached = cache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        executor.execute {
            val bytes = CommunityApi.downloadBytes(url).getOrNull() ?: return@execute
            // Animated WebP decodes to its first frame here, which is what
            // a grid thumbnail needs.
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@execute
            cache.put(url, bitmap)
            imageView.post {
                if (imageView.tag == url) imageView.setImageBitmap(bitmap)
            }
        }
    }
}
