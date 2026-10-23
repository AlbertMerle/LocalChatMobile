package com.localchat.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Screen { Chat, Settings }

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val api = Api()

    val conversation = Conversation()

    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _screen = MutableStateFlow(if (_settings.value.isConfigured) Screen.Chat else Screen.Settings)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _call = MutableStateFlow<CallEngine?>(null)
    val call: StateFlow<CallEngine?> = _call.asStateFlow()

    private var textJob: Job? = null

    fun show(screen: Screen) { _screen.value = screen }

    fun save(s: Settings) {
        store.save(s)
        _settings.value = store.load()
        _screen.value = Screen.Chat
    }

    fun clearChat() {
        stopText()
        conversation.clear()
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _busy.value || _call.value != null) return
        conversation.addUser(t)
        val id = conversation.addAssistant()
        val s = _settings.value
        val history = conversation.toApi(s, callMode = false)
        _busy.value = true
        textJob = viewModelScope.launch {
            val sb = StringBuilder()
            try {
                api.chatStream(s, history).collect {
                    sb.append(it)
                    conversation.setText(id, sb.toString())
                }
                conversation.finish(id)
            } catch (e: CancellationException) {
                conversation.finish(id)
                throw e
            } catch (e: Exception) {
                conversation.fail(id, e.message ?: "request failed")
            } finally {
                _busy.value = false
            }
        }
    }

    fun stopText() { textJob?.cancel() }

    fun startCall() {
        if (_call.value != null) return
        stopText()
        _call.value = CallEngine(viewModelScope, api, { _settings.value }, conversation).also { it.start() }
    }

    fun endCall() {
        _call.value?.stop()
        _call.value = null
    }

    override fun onCleared() { _call.value?.stop() }
}
