package com.foriad.soundstick

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates the built-in sound effects as WAV files, synthesized in code
 * (simple tones / noise shapes - no audio assets needed).
 */
object EffectsSynth {
    private const val SAMPLE_RATE = 22050

    data class Effect(val name: String, val fileName: String)

    val EFFECTS = listOf(
        Effect("Pop!", "fx_pop.wav"),
        Effect("Ding!", "fx_ding.wav"),
        Effect("Drumroll", "fx_drumroll.wav"),
        Effect("Buzzer", "fx_buzzer.wav"),
        Effect("Whistle", "fx_whistle.wav"),
        Effect("Boing!", "fx_boing.wav")
    )

    /** Returns the WAV file for [effect], generating it on first use. */
    fun effectFile(cacheDir: File, effect: Effect): File {
        val dir = File(cacheDir, "effects").apply { mkdirs() }
        val out = File(dir, effect.fileName)
        if (out.exists() && out.length() > 0) return out
        val samples = when (effect.fileName) {
            "fx_pop.wav" -> pop()
            "fx_ding.wav" -> ding()
            "fx_drumroll.wav" -> drumroll()
            "fx_buzzer.wav" -> buzzer()
            "fx_whistle.wav" -> whistle()
            else -> boing()
        }
        writeWav(out, samples)
        return out
    }

    private fun seconds(s: Double): Int = (s * SAMPLE_RATE).toInt()

    private fun pop(): ShortArray {
        val n = seconds(0.18)
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val freq = 650.0 - 2600.0 * t // fast pitch drop
            phase += 2 * PI * freq / SAMPLE_RATE
            val env = exp(-t * 22.0)
            out[i] = (sin(phase) * env * 0.9 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun ding(): ShortArray {
        val n = seconds(0.9)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-t * 3.5)
            val v = sin(2 * PI * 880 * t) * 0.7 + sin(2 * PI * 1318 * t) * 0.3
            out[i] = (v * env * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun drumroll(): ShortArray {
        val n = seconds(1.2)
        val out = ShortArray(n)
        val rnd = Random(42)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            // Rapid hits that speed up, noise-based "roll"
            val hits = if ((t * 28).toInt() % 2 == 0) 1.0 else 0.25
            val env = 0.55 + 0.45 * (t / 1.2)
            val noise = rnd.nextDouble(-1.0, 1.0)
            out[i] = (noise * hits * env * 0.55 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun buzzer(): ShortArray {
        val n = seconds(0.7)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val square = if (sin(2 * PI * 140 * t) >= 0) 1.0 else -1.0
            val fadeIn = minOf(1.0, t * 40)
            val fadeOut = minOf(1.0, (0.7 - t) * 20)
            out[i] = (square * 0.5 * fadeIn * fadeOut * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun whistle(): ShortArray {
        val n = seconds(0.8)
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val freq = 1800.0 + 900.0 * (t / 0.8) + 60 * sin(2 * PI * 25 * t) // slide up + vibrato
            phase += 2 * PI * freq / SAMPLE_RATE
            val env = minOf(1.0, t * 15) * minOf(1.0, (0.8 - t) * 8)
            out[i] = (sin(phase) * env * 0.7 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun boing(): ShortArray {
        val n = seconds(0.65)
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            // Springy pitch: jumps up then wobbles down
            val freq = 160.0 + 480.0 * exp(-t * 5.0) * (0.6 + 0.4 * sin(2 * PI * 9 * t))
            phase += 2 * PI * freq / SAMPLE_RATE
            val env = exp(-t * 4.0)
            out[i] = (sin(phase) * env * 0.85 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** Writes 16-bit PCM mono WAV. */
    fun writeWav(file: File, samples: ShortArray) {
        val dataSize = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(1) // mono
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(SAMPLE_RATE * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        for (s in samples) buffer.putShort(s)
        FileOutputStream(file).use { it.write(buffer.array()) }
    }
}
