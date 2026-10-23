package com.localchat.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

enum class CallPhase { Listening, UserSpeaking, Thinking, Speaking }

private const val MIN_SPEECH_MS = 300   // shorter blips are treated as noise
private const val COMMIT_DELAY_MS = 900L // pause after the last words before the AI is asked to answer

/**
 * Hands-free voice call.
 *
 * - The mic is always on. When the user starts talking, the AI's audio is *paused* at once.
 * - The finished segment goes to STT. Real words → the AI is interrupted for good (its audio is dropped, the
 *   history keeps only what was actually spoken) but its LLM request keeps running unseen while the user talks.
 *   No words (cough, noise) → the AI simply resumes mid-sentence.
 * - After [COMMIT_DELAY_MS] without more speech, everything the user said is sent as one turn.
 *
 * Must be driven from a single-threaded scope (viewModelScope); state is not otherwise synchronised.
 */
class CallEngine(
    private val scope: CoroutineScope,
    private val api: Api,
    private val settings: () -> Settings,
    private val conv: Conversation,
) {
    val phase = MutableStateFlow(CallPhase.Listening)
    val level = MutableStateFlow(0f)
    val heard = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val muted = MutableStateFlow(false)

    private val capture = VoiceCapture { level.value = it }
    private val player = AudioPlayer()
    private val segments = Channel<MicEvent.SpeechEnd>(Channel.UNLIMITED)

    private var root: Job? = null
    private var genJob: Job? = null
    private var speechJob: Job? = null
    private var commitJob: Job? = null

    private var userSpeaking = false
    private var pendingStt = 0
    private val pendingUser = StringBuilder()

    private var assistantId: Long? = null
    private val spoken = StringBuilder()
    private var dropSpeech = false

    fun start() {
        if (root != null) return
        root = scope.launch {
            launch { for (seg in segments) processSegment(seg) }
            try {
                capture.events().collect { ev ->
                    when (ev) {
                        MicEvent.SpeechStart -> onSpeechStart()
                        is MicEvent.SpeechEnd -> { userSpeaking = false; pendingStt++; segments.trySend(ev) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                report(if (e is AppError) e else AppError("MIC-FAIL", "Microphone error: ${e.message}", e.toString()))
            }
        }
    }

    fun stop() {
        root?.cancel(); root = null
        commitJob?.cancel(); genJob?.cancel(); speechJob?.cancel()
        player.paused = false
        assistantId?.let { id -> if (spoken.isBlank()) conv.remove(id) else { conv.setText(id, spoken.toString().trim()); conv.finish(id) } }
        assistantId = null
    }

    private fun report(e: AppError) {
        ErrorLog.report(e)
        error.value = e.display
    }

    fun setMuted(m: Boolean) { muted.value = m; capture.muted = m }

    private fun setPhase(p: CallPhase) {
        phase.value = p
        capture.aiSpeaking = p == CallPhase.Speaking
    }

    private fun onSpeechStart() {
        userSpeaking = true
        commitJob?.cancel()
        if (speechJob?.isActive == true) player.paused = true // hold the AI's voice until STT confirms words
        setPhase(CallPhase.UserSpeaking)
    }

    private suspend fun processSegment(seg: MicEvent.SpeechEnd) {
        val text = if (seg.speechMs < MIN_SPEECH_MS) "" else try {
            cleanTranscript(api.transcribe(settings(), seg.wav))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(classify(Svc.STT, settings().sttBase, e))
            ""
        }
        pendingStt--

        if (text.isNotEmpty()) {
            if (pendingUser.isEmpty()) confirmInterrupt()
            if (pendingUser.isNotEmpty()) pendingUser.append(' ')
            pendingUser.append(text)
            heard.value = pendingUser.toString()
        }
        if (userSpeaking || pendingStt > 0) return

        if (pendingUser.isEmpty()) { // false alarm: carry on where the AI left off
            player.paused = false
            setPhase(
                when {
                    speechJob?.isActive == true -> CallPhase.Speaking
                    genJob?.isActive == true -> CallPhase.Thinking
                    else -> CallPhase.Listening
                }
            )
            return
        }
        setPhase(CallPhase.Thinking)
        commitJob?.cancel()
        commitJob = scope.launch { delay(COMMIT_DELAY_MS); commit() }
    }

    /** Words were really spoken: stop the AI's audio for good, but leave its generation running quietly. */
    private fun confirmInterrupt() {
        speechJob?.cancel(); speechJob = null
        player.paused = false
        dropSpeech = true
        assistantId?.let { id ->
            if (spoken.isBlank()) conv.remove(id) else conv.setText(id, spoken.toString().trim())
            if (spoken.isNotBlank()) conv.finish(id)
        }
        assistantId = null
    }

    private fun commit() {
        val text = pendingUser.toString().trim()
        pendingUser.clear()
        heard.value = ""
        genJob?.cancel(); genJob = null // the unseen background answer is obsolete now
        if (text.isEmpty()) { setPhase(CallPhase.Listening); return }
        error.value = null
        conv.addUser(text)
        startGeneration()
    }

    private fun startGeneration() {
        dropSpeech = false
        spoken.clear()
        val id = conv.addAssistant()
        assistantId = id
        val queue = Channel<String>(Channel.UNLIMITED)
        speechJob = scope.launch { runSpeech(queue) }
        setPhase(CallPhase.Thinking)

        val history = conv.toApi(settings(), callMode = true)
        genJob = scope.launch {
            val full = StringBuilder()
            val chunker = SentenceChunker()
            try {
                api.chatStream(settings(), history).collect { delta ->
                    full.append(delta)
                    if (!dropSpeech) {
                        conv.setText(id, full.toString())
                        chunker.feed(delta).forEach { queue.trySend(it) }
                    }
                }
                if (!dropSpeech) {
                    chunker.flush()?.let { queue.trySend(it) }
                    conv.finish(id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = classify(Svc.LLM, settings().llmBase, e)
                report(err)
                if (!dropSpeech) conv.fail(id, err.display)
            } finally {
                queue.close()
            }
        }
    }

    /** TTS-fetches sentences one ahead of playback so there is no gap between them. */
    private suspend fun runSpeech(queue: Channel<String>) {
        val clips = Channel<Clip>(2)
        coroutineScope {
            launch {
                try {
                    for (sentence in queue) {
                        val clip = try {
                            withContext(Dispatchers.IO) { api.synthesize(settings(), sentence) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            report(classify(Svc.TTS, settings().ttsBase, e))
                            continue
                        }
                        clips.send(clip)
                    }
                } finally {
                    clips.close()
                }
            }
            for (clip in clips) {
                if (!userSpeaking) setPhase(CallPhase.Speaking)
                spoken.append(clip.text).append(' ')
                try {
                    player.play(clip)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report(AppError("AUDIO-PLAY", "Couldn't play audio: ${e.message}", e.toString()))
                }
            }
        }
        // Reached only when the whole reply was spoken (cancellation skips this).
        if (!userSpeaking && pendingUser.isEmpty()) setPhase(CallPhase.Listening)
    }
}
