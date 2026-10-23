package com.localchat.mobile

import android.content.Context

const val DEFAULT_LLM_PORT = 18789 // OpenClaw gateway
const val DEFAULT_STT_PORT = 8081
const val DEFAULT_TTS_PORT = 8082

// Fixed request values; local servers generally ignore the STT/TTS ones.
const val LLM_MODEL = "openclaw/default"
const val STT_MODEL = "whisper-1"
const val TTS_MODEL = "tts-1"
const val TTS_VOICE = "alloy"

/**
 * Connection settings: one IP plus an optional port per service. A blank port means the default.
 * URLs are `http://<ip>:<port>/v1`.
 */
data class Settings(
    val host: String = "",
    val apiKey: String = "",
    val llmPort: String = "",
    val sttPort: String = "",
    val ttsPort: String = "",
) {
    /** Host with any scheme, port or path the user may have pasted in stripped off. */
    val cleanHost: String
        get() = host.trim()
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':')

    val llmBase get() = url(llmPort, DEFAULT_LLM_PORT)
    val sttBase get() = url(sttPort, DEFAULT_STT_PORT)
    val ttsBase get() = url(ttsPort, DEFAULT_TTS_PORT)

    val isConfigured get() = cleanHost.isNotEmpty()

    private fun url(port: String, default: Int): String {
        if (cleanHost.isEmpty()) return ""
        val p = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: default
        return "http://$cleanHost:$p/v1"
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        fun s(k: String) = prefs.getString(k, "") ?: ""
        return Settings(s("host"), s("apiKey"), s("llmPort"), s("sttPort"), s("ttsPort"))
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("host", s.host.trim())
            .putString("apiKey", s.apiKey.trim())
            .putString("llmPort", s.llmPort.trim())
            .putString("sttPort", s.sttPort.trim())
            .putString("ttsPort", s.ttsPort.trim())
            .apply()
    }
}
