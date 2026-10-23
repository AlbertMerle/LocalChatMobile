package com.localchat.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogicTest {
    @Test fun defaultPortsFromIp() {
        val s = Settings(host = "192.168.12.24")
        assertEquals("http://192.168.12.24:8080/v1", s.llmBase)
        assertEquals("http://192.168.12.24:8081/v1", s.sttBase)
        assertEquals("http://192.168.12.24:8082/v1", s.ttsBase)
    }

    @Test fun pastedUrlIsReducedToHost() {
        assertEquals("http://10.0.0.5:8080/v1", Settings(host = "http://10.0.0.5/v1").llmBase)
        assertEquals("http://10.0.0.5:8082/v1", Settings(host = "10.0.0.5:9999").ttsBase)
    }

    @Test fun overrideWins() {
        val s = Settings(host = "10.0.0.5", sttUrl = "10.0.0.9:9000/v1/")
        assertEquals("http://10.0.0.9:9000/v1", s.sttBase)
        assertEquals("http://10.0.0.5:8080/v1", s.llmBase)
    }

    @Test fun unconfiguredWhenBlank() {
        assertEquals(false, Settings().isConfigured)
    }

    @Test fun thinkFilterAcrossDeltas() {
        val f = ThinkFilter()
        val out = listOf("Hi <th", "ink>secret", " stuff</thi", "nk> there <", "b>").joinToString("") { f.feed(it) }
        assertEquals("Hi  there <b>", out)
    }

    @Test fun sentenceChunker() {
        val c = SentenceChunker()
        val out = mutableListOf<String>()
        listOf("Hello there, how are you doing today", "? I'm *fine*. Thanks", " for asking.").forEach { out += c.feed(it) }
        c.flush()?.let(out::add)
        assertEquals(listOf("Hello there, how are you doing today?", "I'm fine. Thanks for asking."), out)
    }

    @Test fun transcriptNoiseIsDropped() {
        assertEquals("", cleanTranscript("[BLANK_AUDIO]"))
        assertEquals("hello world", cleanTranscript("(coughs) hello world"))
        assertNull(clean("***"))
    }

    @Test fun wavRoundTrip() {
        val pcm = ByteArray(200) { it.toByte() }
        val clip = parseWav("x", pcmToWav(pcm, 16000))
        assertEquals(16000, clip.sampleRate)
        assertEquals(1, clip.channels)
        assertEquals(pcm.toList(), clip.pcm.toList())
    }
}

class ErrorTest {
    @Test fun codes() {
        val u = "http://x:8081/v1/audio/transcriptions"
        assertEquals("STT-HTTP-401", classify(Svc.STT, u, HttpStatusException(401, "")).code)
        assertEquals("LLM-CONN", classify(Svc.LLM, u, java.net.ConnectException("refused")).code)
        assertEquals("TTS-DNS", classify(Svc.TTS, u, java.net.UnknownHostException("x")).code)
        assertEquals("STT-TIMEOUT", classify(Svc.STT, u, java.net.SocketTimeoutException()).code)
        assertEquals("TTS-BAD-AUDIO", classify(Svc.TTS, u, IllegalStateException("bad")).code)
    }
}
