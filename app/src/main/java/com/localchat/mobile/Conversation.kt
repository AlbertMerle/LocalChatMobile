package com.localchat.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class ApiMessage(val role: String, val content: String)

data class ChatMessage(
    val id: Long,
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val error: String? = null,
)

private const val VOICE_HINT =
    "You are in a live voice call. Reply in short, natural spoken sentences. " +
        "Do not use markdown, lists, code blocks or emoji."

/** Single shared thread used by both text chat and calls. */
class Conversation {
    private var nextId = 1L
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    fun addUser(text: String): Long = add("user", text, streaming = false)
    fun addAssistant(): Long = add("assistant", "", streaming = true)

    private fun add(role: String, text: String, streaming: Boolean): Long {
        val id = nextId++
        messages.update { it + ChatMessage(id, role, text, streaming) }
        return id
    }

    private fun edit(id: Long, f: (ChatMessage) -> ChatMessage) =
        messages.update { list -> list.map { if (it.id == id) f(it) else it } }

    fun setText(id: Long, text: String) = edit(id) { it.copy(text = text) }
    fun finish(id: Long) = edit(id) { it.copy(streaming = false) }
    fun fail(id: Long, error: String) = edit(id) { it.copy(streaming = false, error = error) }
    fun remove(id: Long) = messages.update { list -> list.filter { it.id != id } }
    fun clear() { messages.value = emptyList() }

    /** Builds the request history. Consecutive same-role messages are merged (some servers require alternation). */
    fun toApi(s: Settings, callMode: Boolean): List<ApiMessage> {
        val out = mutableListOf<ApiMessage>()
        if (callMode) out += ApiMessage("system", VOICE_HINT)
        for (m in messages.value) {
            if (m.text.isBlank()) continue
            val last = out.lastOrNull()
            if (last != null && last.role == m.role && m.role != "system") {
                out[out.lastIndex] = last.copy(content = last.content + "\n" + m.text)
            } else {
                out += ApiMessage(m.role, m.text)
            }
        }
        return out
    }
}
