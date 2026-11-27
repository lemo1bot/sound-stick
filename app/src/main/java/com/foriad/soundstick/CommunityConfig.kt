package com.foriad.soundstick

/**
 * Community feed backend configuration (Supabase).
 *
 * These are the project's public URL and anon key — the anon key is
 * designed to be embedded in apps; row-level-security policies on the
 * backend govern what it can read and write.
 */
object CommunityConfig {
    const val SUPABASE_URL = "https://ekhfzalwxmsypladimjx.supabase.co"
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImVraGZ6YWx3eG1zeXBsYWRpbWp4Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTU3OTAyNzAsImV4cCI6MjExMTM2NjI3MH0.aeXlNsLmf677VRuDVDoFkUk1K4i-hZ25VON4JZV4uAk"

    /** Name shown as the author of published stickers (no accounts yet). */
    const val PUBLISHER_NAME = "SoundStick user"

    /** Storage bucket that holds community sticker image/audio files. */
    const val BUCKET = "stickers"

    /** REST table with the community sticker rows. */
    const val TABLE = "community_stickers"

    val isConfigured: Boolean
        get() = SUPABASE_URL.startsWith("https://") && SUPABASE_ANON_KEY.length > 20
}
