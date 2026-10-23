# LocalChat Mobile

A tiny Android app (Kotlin + Compose, ~1.2 MB release APK) for texting and calling an AI agent
(e.g. OpenClaw) running on your own network.

## Setup

1. Install the APK (see *Build*).
2. Open **Settings**, type the server's IP (e.g. `192.168.12.24`) and your API key.

The IP expands to three OpenAI-compatible endpoints; any of them can be overridden in Settings:

| Service | Default URL | Path used |
|---|---|---|
| AI chat (streaming) | `http://<ip>:8080/v1` | `/chat/completions` |
| Speech-to-text | `http://<ip>:8081/v1` | `/audio/transcriptions` |
| Text-to-speech | `http://<ip>:8082/v1` | `/audio/speech` (asks for `wav`) |

The API key is sent as `Authorization: Bearer <key>` to all three.

## Calls

Tap the phone icon. The mic stays open (with echo cancellation) so you can interrupt at any time:

- You start talking → the AI's voice **pauses instantly**.
- Your speech goes to STT. If it contains words, the AI's audio is dropped and the history keeps only what
  it actually said; its generation keeps running unseen while you talk. If STT hears nothing
  (cough, noise), the AI **resumes mid-sentence**.
- ~0.9 s after you stop, everything you said is sent as one turn. You can pause mid-thought without being cut off.

Use headphones or modest speaker volume for the best barge-in. Tunables are constants at the top of
`VoiceCapture.kt` and `CallEngine.kt`.

## Build

`./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (signed with the debug key so it
sideloads directly). The GitHub Actions workflow builds it on every push and uploads the APK as an artifact.

## Download

A prebuilt APK is committed at [`dist/LocalChat.apk`](dist/LocalChat.apk). Open it on your phone
(GitHub app/browser → Download) and allow "install unknown apps" when asked.

## Errors

Every failure shows a short code and is kept under **Errors** (top bar) → **Copy all**, which copies a report
(device, URLs, no API key) you can paste to whoever is debugging. Settings has a **Test connection** button
that checks all three ports.

| Code | Meaning |
|---|---|
| `LLM-/STT-/TTS-CONN` | Can't connect: server down, wrong port, or phone on another network |
| `…-DNS` | Address can't be resolved (typo in IP) |
| `…-TIMEOUT` | Server didn't answer in time |
| `…-HTTP-401/403` | API key rejected |
| `…-HTTP-404` | Wrong path (URL should end in `/v1`) |
| `…-HTTP-400/422` | Request refused, usually wrong model or voice name |
| `…-HTTP-5xx` | Server-side failure |
| `…-BAD-RESPONSE` | Reply wasn't the expected JSON |
| `TTS-BAD-AUDIO` | TTS didn't return 16-bit WAV |
| `LLM-SERVER-ERROR` | Error object inside the chat stream |
| `MIC-PERMISSION` / `MIC-INIT` / `MIC-FAIL` | Microphone denied / couldn't start / failed |
| `AUDIO-PLAY` | Playback failed |
