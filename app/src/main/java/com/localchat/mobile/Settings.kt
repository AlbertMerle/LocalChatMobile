package com.localchat.mobile

import android.content.Context

const val DEFAULT_LLM_PORT = 8080
const val DEFAULT_STT_PORT = 8081
const val DEFAULT_TTS_PORT = 8082

/**
 * Connection settings. [host] is just the IP/hostname; the three service URLs are derived from it
 * (`http://host:8080/v1`, `:8081/v1`, `:8082/v1`) unless an override is filled in.
 */
data class Settings(
    val host: String = "",
    val apiKey: String = "",
    val llmUrl: String = "",
    val sttUrl: String = "",
    val ttsUrl: String = "",
    val llmModel: String = "default",
    val sttModel: String = "whisper-1",
    val ttsModel: String = "tts-1",
    val voice: String = "alloy",
    val systemPrompt: String = "",
) {
    /** Host with any scheme, port or path the user may have pasted in stripped off. */
    val cleanHost: String
        get() = host.trim()
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':')

    val llmBase get() = resolve(llmUrl, DEFAULT_LLM_PORT)
    val sttBase get() = resolve(sttUrl, DEFAULT_STT_PORT)
    val ttsBase get() = resolve(ttsUrl, DEFAULT_TTS_PORT)

    val isConfigured get() = llmBase.isNotEmpty()

    fun defaultUrl(port: Int) = if (cleanHost.isEmpty()) "http://<ip>:$port/v1" else "http://$cleanHost:$port/v1"

    private fun resolve(override: String, port: Int): String {
        val o = override.trim()
        if (o.isNotEmpty()) {
            val withScheme = if (o.startsWith("http://") || o.startsWith("https://")) o else "http://$o"
            return withScheme.trimEnd('/')
        }
        return if (cleanHost.isEmpty()) "" else defaultUrl(port)
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        fun s(k: String, def: String) = prefs.getString(k, def) ?: def
        return Settings(
            host = s("host", d.host),
            apiKey = s("apiKey", d.apiKey),
            llmUrl = s("llmUrl", d.llmUrl),
            sttUrl = s("sttUrl", d.sttUrl),
            ttsUrl = s("ttsUrl", d.ttsUrl),
            llmModel = s("llmModel", d.llmModel),
            sttModel = s("sttModel", d.sttModel),
            ttsModel = s("ttsModel", d.ttsModel),
            voice = s("voice", d.voice),
            systemPrompt = s("systemPrompt", d.systemPrompt),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("host", s.host.trim())
            .putString("apiKey", s.apiKey.trim())
            .putString("llmUrl", s.llmUrl.trim())
            .putString("sttUrl", s.sttUrl.trim())
            .putString("ttsUrl", s.ttsUrl.trim())
            .putString("llmModel", s.llmModel.trim())
            .putString("sttModel", s.sttModel.trim())
            .putString("ttsModel", s.ttsModel.trim())
            .putString("voice", s.voice.trim())
            .putString("systemPrompt", s.systemPrompt)
            .apply()
    }
}
