package com.foriad.soundstick

import android.media.MediaPlayer
import java.io.File

/** Tiny shared MediaPlayer helper: plays one sound at a time. */
object SoundPlayer {
    private var player: MediaPlayer? = null

    fun play(file: File, onDone: () -> Unit = {}) {
        stop()
        if (!file.exists()) {
            onDone()
            return
        }
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stop()
                    onDone()
                }
                setOnErrorListener { _, _, _ ->
                    stop()
                    onDone()
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            onDone()
        }
    }

    fun stop() {
        try {
            player?.stop()
        } catch (e: Exception) {
            // already stopped
        }
        player?.release()
        player = null
    }
}
