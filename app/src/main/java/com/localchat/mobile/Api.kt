package com.localchat.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val JSON = "application/json".toMediaType()

/** Thin client for the three OpenAI-compatible local services (chat, transcriptions, speech). */
class Api {
    private val stream = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val short = stream.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    private fun request(url: String, key: String) = Request.Builder().url(url).apply {
        if (key.isNotBlank()) header("Authorization", "Bearer $key")
    }

    /** Streams assistant text deltas from `POST {llm}/chat/completions` (SSE). */
    fun chatStream(s: Settings, messages: List<ApiMessage>): Flow<String> = channelFlow {
        val body = JSONObject()
            .put("model", s.llmModel)
            .put("stream", true)
            .put("messages", JSONArray().also { arr ->
                messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
        val url = "${s.llmBase}/chat/completions"
        val call = stream.newCall(
            request(url, s.apiKey)
                .post(body.toString().toRequestBody(JSON)).build()
        )
        launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw httpError(resp)
                    val think = ThinkFilter()
                    val source = resp.body!!.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.substring(5).trim()
                        if (payload == "[DONE]") break
                        val obj = JSONObject(payload)
                        obj.optJSONObject("error")?.let {
                            throw AppError("LLM-SERVER-ERROR", "AI chat: the server reported an error: ${it.optString("message", it.toString()).take(200)}", url)
                        }
                        val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                        if (delta.isNull("content")) continue
                        val text = think.feed(delta.getString("content"))
                        if (text.isNotEmpty()) send(text)
                    }
                }
                close()
            } catch (e: Exception) {
                if (call.isCanceled()) close() else close(classify(Svc.LLM, url, e))
            }
        }
        awaitClose { call.cancel() }
    }

    /** `POST {stt}/audio/transcriptions` with a WAV body; returns the recognised text. */
    suspend fun transcribe(s: Settings, wav: ByteArray): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", s.sttModel)
            .addFormDataPart("response_format", "json")
            .build()
        val url = "${s.sttBase}/audio/transcriptions"
        try {
            short.newCall(request(url, s.apiKey).post(form).build()).await().use {
                if (!it.isSuccessful) throw httpError(it)
                return JSONObject(it.body!!.string()).optString("text", "")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw classify(Svc.STT, url, e)
        }
    }

    /** `POST {tts}/audio/speech`; returns a WAV clip (raw 24 kHz PCM is assumed if no RIFF header). */
    suspend fun synthesize(s: Settings, text: String): Clip {
        val body = JSONObject()
            .put("model", s.ttsModel)
            .put("input", text)
            .put("voice", s.voice)
            .put("response_format", "wav")
        val url = "${s.ttsBase}/audio/speech"
        try {
            short.newCall(request(url, s.apiKey).post(body.toString().toRequestBody(JSON)).build()).await().use {
                if (!it.isSuccessful) throw httpError(it)
                return parseWav(text, it.body!!.bytes())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw classify(Svc.TTS, url, e)
        }
    }

    private fun httpError(resp: Response): HttpStatusException =
        HttpStatusException(resp.code, runCatching { resp.body?.string().orEmpty() }.getOrDefault(""))

    /** Reachability check for the Settings screen. Returns null when the endpoint answers sensibly. */
    suspend fun probe(svc: Svc, s: Settings): AppError? {
        val base = when (svc) { Svc.LLM -> s.llmBase; Svc.STT -> s.sttBase; else -> s.ttsBase }
        val url = if (svc == Svc.LLM) "$base/models" else base
        return try {
            short.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
                .newCall(request(url, s.apiKey).get().build()).await().use {
                    // Any non-auth answer proves the port is up; /models or the bare base may legitimately 404.
                    if (it.code == 401 || it.code == 403 || it.code >= 500) throw httpError(it)
                    null
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            classify(svc, url, e)
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
    })
}

/** Removes `<think>…</think>` blocks that some local reasoning models emit inline. */
class ThinkFilter {
    private val pending = StringBuilder()
    private var inThink = false

    fun feed(delta: String): String {
        pending.append(delta)
        val out = StringBuilder()
        while (true) {
            if (inThink) {
                val i = pending.indexOf(CLOSE)
                if (i < 0) {
                    if (pending.length > CLOSE.length) pending.delete(0, pending.length - CLOSE.length)
                    break
                }
                pending.delete(0, i + CLOSE.length)
                inThink = false
            } else {
                val i = pending.indexOf(OPEN)
                if (i >= 0) {
                    out.append(pending, 0, i)
                    pending.delete(0, i + OPEN.length)
                    inThink = true
                } else {
                    // Hold back a possible partial "<think" at the end of the buffer.
                    var keep = 0
                    for (n in minOf(OPEN.length - 1, pending.length) downTo 1) {
                        if (OPEN.startsWith(pending.substring(pending.length - n))) { keep = n; break }
                    }
                    out.append(pending, 0, pending.length - keep)
                    pending.delete(0, pending.length - keep)
                    break
                }
            }
        }
        return out.toString()
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
