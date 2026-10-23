package com.localchat.mobile

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Plays one clip at a time. [paused] freezes output instantly (used while the user may be talking);
 * cancelling the calling coroutine silences it immediately.
 */
class AudioPlayer {
    @Volatile var paused = false

    suspend fun play(clip: Clip) = withContext(Dispatchers.IO) {
        val mask = if (clip.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val frameBytes = 2 * clip.channels
        val minBuf = AudioTrack.getMinBufferSize(clip.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(clip.sampleRate)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, clip.sampleRate * frameBytes / 5)) // ~200 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            val chunk = clip.sampleRate * frameBytes / 50 // 20 ms
            val totalFrames = clip.pcm.size / frameBytes
            var off = 0

            suspend fun honourPause() {
                if (paused) {
                    track.pause()
                    while (paused) { ensureActive(); delay(20) }
                    track.play()
                }
            }

            while (off < clip.pcm.size) {
                ensureActive()
                honourPause()
                val n = track.write(clip.pcm, off, minOf(chunk, clip.pcm.size - off))
                if (n < 0) break
                off += n
            }
            // Let the tail drain.
            while (track.playbackHeadPosition < totalFrames) {
                ensureActive()
                honourPause()
                delay(20)
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { track.pause(); track.flush() }
                track.release()
            }
        }
    }
}
