package com.localchat.mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A decoded 16-bit PCM clip ready for [AudioPlayer]. */
class Clip(val text: String, val pcm: ByteArray, val sampleRate: Int, val channels: Int)

fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
    val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
    b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
    b.put("data".toByteArray()).putInt(pcm.size).put(pcm)
    return b.array()
}

/** Parses a PCM16 WAV. Streaming servers often write bogus chunk sizes, so "data" runs to EOF when needed. */
fun parseWav(text: String, bytes: ByteArray): Clip {
    val isRiff = bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE"
    if (!isRiff) return Clip(text, bytes, 24000, 1) // raw "pcm" output of OpenAI-style servers

    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var rate = 24000
    var channels = 1
    var pos = 12
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4)
        val size = b.getInt(pos + 4).toLong() and 0xFFFFFFFFL
        val body = pos + 8
        when (id) {
            "fmt " -> {
                val bits = b.getShort(body + 14).toInt()
                if (bits != 16) throw IllegalStateException("TTS returned $bits-bit audio; 16-bit PCM WAV is required")
                channels = b.getShort(body + 2).toInt()
                rate = b.getInt(body + 4)
            }
            "data" -> {
                val len = if (size == 0L || body + size > bytes.size) bytes.size - body else size.toInt()
                return Clip(text, bytes.copyOfRange(body, body + len), rate, channels)
            }
        }
        pos = body + size.toInt() + (size.toInt() and 1)
    }
    throw IllegalStateException("TTS response had no audio data (is the server returning WAV?)")
}
