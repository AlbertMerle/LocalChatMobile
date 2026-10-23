package com.localchat.mobile

import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException

enum class Svc(val label: String, val tag: String) {
    LLM("AI chat", "LLM"),
    STT("Speech-to-text", "STT"),
    TTS("Text-to-speech", "TTS"),
    MIC("Microphone", "MIC"),
    AUDIO("Audio playback", "AUDIO"),
}

/** An error with a stable short code (e.g. `STT-HTTP-401`) so it can be quoted in a bug report. */
class AppError(val code: String, val summary: String, val detail: String = "") : Exception("[$code] $summary") {
    val display get() = "[$code] $summary"
}

class HttpStatusException(val status: Int, val body: String) : IOException("HTTP $status")

/** Turns any exception from a service call into an [AppError] with a code and a hint on what to check. */
fun classify(svc: Svc, url: String, e: Throwable): AppError {
    if (e is AppError) return e
    val t = svc.tag
    val raw = "${e.javaClass.simpleName}: ${e.message}"
    fun err(code: String, summary: String) = AppError("$t-$code", summary, "$url\n$raw")
    return when {
        e is UnknownHostException ->
            err("DNS", "${svc.label}: can't resolve the server address. Check the IP in Settings.")
        e is ConnectException || e is NoRouteToHostException ->
            err("CONN", "${svc.label}: can't connect. Is the server running on that port, and is the phone on the same network?")
        e is SocketTimeoutException ->
            err("TIMEOUT", "${svc.label}: the server didn't answer in time.")
        e is SSLException ->
            err("TLS", "${svc.label}: TLS/SSL failure. Local servers usually want http://, not https://.")
        e is HttpStatusException -> {
            val hint = when (e.status) {
                401, 403 -> "the server rejected the API key"
                404 -> "endpoint not found; check the URL/path (it should end in /v1)"
                400, 422 -> "the server rejected the request; check the model/voice name in Settings"
                429 -> "too many requests"
                in 500..599 -> "the server itself failed"
                else -> "unexpected response"
            }
            val body = e.body.trim().take(200)
            AppError("$t-HTTP-${e.status}", "${svc.label}: HTTP ${e.status}, $hint.", "$url\n$body")
        }
        e is JSONException ->
            err("BAD-RESPONSE", "${svc.label}: the server's reply wasn't the expected JSON.")
        e is IllegalStateException && svc == Svc.TTS ->
            err("BAD-AUDIO", e.message ?: "TTS returned unusable audio.")
        e is IOException ->
            err("IO", "${svc.label}: network error (${e.message}).")
        else -> err("UNKNOWN", "${svc.label}: unexpected error (${e.javaClass.simpleName}).")
    }
}

data class LogEntry(val time: Long, val code: String, val message: String, val detail: String)

/** In-memory log of the last errors, viewable and copyable from the app. */
object ErrorLog {
    val entries = MutableStateFlow<List<LogEntry>>(emptyList())

    fun report(e: AppError) {
        Log.e("LocalChat", "${e.display}\n${e.detail}")
        entries.update { (listOf(LogEntry(System.currentTimeMillis(), e.code, e.summary, e.detail)) + it).take(100) }
    }

    fun clear() { entries.value = emptyList() }

    fun report(svc: Svc, url: String, e: Throwable): AppError = classify(svc, url, e).also(::report)

    /** Plain-text dump to paste into a bug report. Never includes the API key. */
    fun dump(s: Settings): String = buildString {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        appendLine("LocalChat 1.0 | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("LLM=${s.llmBase}  STT=${s.sttBase}  TTS=${s.ttsBase}  key=${if (s.apiKey.isBlank()) "none" else "set"}")
        appendLine()
        if (entries.value.isEmpty()) appendLine("(no errors logged)")
        entries.value.forEach {
            appendLine("${fmt.format(Date(it.time))} [${it.code}] ${it.message}")
            if (it.detail.isNotBlank()) appendLine("    " + it.detail.replace("\n", "\n    "))
        }
    }
}
