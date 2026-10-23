package com.localchat.mobile

/** Splits a stream of text deltas into sentences so TTS can start before the reply is complete. */
class SentenceChunker {
    private val b = StringBuilder()

    fun feed(delta: String): List<String> {
        b.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val end = findEnd()
            if (end < 0) break
            clean(b.substring(0, end))?.let(out::add)
            b.delete(0, end)
        }
        return out
    }

    fun flush(): String? = clean(b.toString()).also { b.clear() }

    private fun findEnd(): Int {
        for (i in b.indices) {
            val c = b[i]
            if (c == '\n') return i + 1
            val terminal = c == '.' || c == '!' || c == '?' || c == '…'
            if (terminal && i + 1 < b.length && b[i + 1].isWhitespace() && i >= MIN_LEN) return i + 1
        }
        return -1
    }

    private companion object {
        const val MIN_LEN = 16
    }
}

/** Strips markdown noise and returns null when nothing speakable remains. */
fun clean(text: String): String? {
    val t = text.replace(Regex("[*#`>~_]+"), "").replace(Regex("\\s+"), " ").trim()
    return t.takeIf { it.any(Char::isLetterOrDigit) }
}

/** Drops bracketed non-speech tags and one-letter junk that Whisper-style models emit for noise. */
fun cleanTranscript(text: String): String {
    val t = text.replace(Regex("\\[[^\\]]*]|\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()
    return if (t.count(Char::isLetterOrDigit) < 2) "" else t
}
