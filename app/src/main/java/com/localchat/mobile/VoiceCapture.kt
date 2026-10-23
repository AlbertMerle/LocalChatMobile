package com.localchat.mobile

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

sealed interface MicEvent {
    data object SpeechStart : MicEvent
    class SpeechEnd(val wav: ByteArray, val speechMs: Int) : MicEvent
}

private const val RATE = 16000
private const val FRAME = RATE / 50          // 20 ms
private const val START_FRAMES = 8           // 160 ms of voice to count as speech
private const val END_FRAMES = 35            // 700 ms of silence ends a segment
private const val MAX_FRAMES = 50 * 30       // hard cap: 30 s per segment
private const val PRE_ROLL_FRAMES = 15       // 300 ms kept from before the trigger
private const val MIN_THRESHOLD = 450f

/** Always-on mic with a simple adaptive energy VAD. Emits speech start and finished (WAV) segments. */
class VoiceCapture(private val onLevel: (Float) -> Unit) {
    /** Raises the trigger threshold while the AI is talking so its own voice doesn't count as the user. */
    @Volatile var aiSpeaking = false
    @Volatile var muted = false

    @SuppressLint("MissingPermission")
    fun events(): Flow<MicEvent> = channelFlow {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME * 2 * 8),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw AppError("MIC-INIT", "Microphone couldn't start. Is another app using it, or is the permission denied?")
        }
        val fx = buildList {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; add(it) }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; add(it) }
        }
        launch(Dispatchers.IO) {
            rec.startRecording()
            val buf = ShortArray(FRAME)
            val preRoll = ArrayDeque<ShortArray>()
            val segment = ArrayList<ShortArray>()
            var noiseFloor = 300f
            var inSpeech = false
            var speechRun = 0
            var silenceRun = 0
            var voiceFrames = 0

            fun reset() { inSpeech = false; speechRun = 0; silenceRun = 0; voiceFrames = 0; preRoll.clear(); segment.clear() }

            while (isActive) {
                val n = rec.read(buf, 0, FRAME)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / n).toFloat()
                onLevel((rms / 6000f).coerceIn(0f, 1f))

                if (muted) { if (inSpeech || preRoll.isNotEmpty()) reset(); continue }

                val threshold = maxOf(MIN_THRESHOLD, noiseFloor * 3f) * (if (aiSpeaking) 1.6f else 1f)
                val loud = rms > threshold
                val frame = buf.copyOf(n)

                if (!inSpeech) {
                    if (!loud) noiseFloor = (noiseFloor * 0.98f + rms * 0.02f).coerceAtMost(1500f)
                    preRoll.addLast(frame)
                    if (preRoll.size > PRE_ROLL_FRAMES + START_FRAMES) preRoll.removeFirst()
                    speechRun = if (loud) speechRun + 1 else 0
                    if (speechRun >= START_FRAMES) {
                        inSpeech = true
                        segment.addAll(preRoll)
                        preRoll.clear()
                        silenceRun = 0
                        voiceFrames = speechRun
                        send(MicEvent.SpeechStart)
                    }
                } else {
                    segment.add(frame)
                    if (loud) { silenceRun = 0; voiceFrames++ } else silenceRun++
                    if (silenceRun >= END_FRAMES || segment.size >= MAX_FRAMES) {
                        val out = ByteArrayOutputStream(segment.size * FRAME * 2)
                        for (f in segment) for (s in f) { out.write(s.toInt() and 0xFF); out.write((s.toInt() shr 8) and 0xFF) }
                        val ms = voiceFrames * 20
                        reset()
                        send(MicEvent.SpeechEnd(pcmToWav(out.toByteArray(), RATE), ms))
                    }
                }
            }
        }
        awaitClose {
            runCatching { rec.stop() }
            fx.forEach { it.release() }
            rec.release()
        }
    }
}
