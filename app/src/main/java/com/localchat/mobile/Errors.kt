package com.localchat.mobile

import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONException
import java.io.IOException
import java.io.InterruptedIOException
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
        e is ConnectException || e is NoRouteToHostException -> {
            val claw = if (svc == Svc.LLM) " OpenClaw listens on localhost only by default: run `openclaw config set gateway.bind lan` and restart the gateway." else ""
            err("CONN", "${svc.label}: connection refused. Nothing is listening on that port.$claw")
        }
        e is InterruptedIOException && e.message?.contains("connect", ignoreCase = true) == true -> {
            val claw = if (svc == Svc.LLM) " For OpenClaw also check gateway.bind (needs `lan`)." else ""
            err("CONN-TIMEOUT", "${svc.label}: no reply while connecting. A firewall on the server is probably dropping this port, or the IP is wrong.$claw")
        }
        e is InterruptedIOException ->
            err("TIMEOUT", "${svc.label}: connected, but the server didn't answer in time.")
        e is SSLException ->
            err("TLS", "${svc.label}: TLS/SSL failure. Local servers usually want http://, not https://.")
        e is HttpStatusException -> {
            val llm = svc == Svc.LLM
            val hint = when (e.status) {
                401, 403 -> if (llm) "OpenClaw rejected the key; use the gateway token or password (gateway.auth.*)" else "the server rejected the API key"
                404 -> if (llm) "OpenClaw's chat endpoint is off or the port is wrong; set gateway.http.endpoints.chatCompletions.enabled = true"
                       else "endpoint not found; check the URL/path (it should end in /v1)"
                400 -> if (llm) "OpenClaw refused the request (model should be openclaw/default)" else "the server rejected the request; check the model/voice name"
                408 -> "the server timed out reading the request"
                409 -> "the OpenClaw session is archived; unarchive it in the Control UI"
                422 -> "the server rejected the request; check the model/voice name"
                429 -> "rate limited (often repeated bad auth); wait and retry"
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
