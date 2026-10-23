# LocalChat Mobile

A tiny Android app (Kotlin + Compose, ~1.2 MB release APK) for texting and calling an AI agent
(e.g. OpenClaw) running on your own network.

## Setup

Open **Settings**, enter the server IP (e.g. `192.168.12.24`) and your API key. That's it: the app uses

| Service | Default port | URL | Path |
|---|---|---|---|
| AI chat (OpenClaw gateway) | `18789` | `http://<ip>:18789/v1` | `/chat/completions`, model `openclaw/default` |
| Speech-to-text | `8081` | `http://<ip>:8081/v1` | `/audio/transcriptions` |
| Text-to-speech | `8082` | `http://<ip>:8082/v1` | `/audio/speech` (asks for `wav`) |

Each port can be changed in Settings (e.g. chat to `8080`); leave a port blank to keep its default.
The API key is sent as `Authorization: Bearer <key>` to all three. OpenClaw's chat endpoint is off by default:
enable `gateway.http.endpoints.chatCompletions.enabled`; the key is your gateway token/password. Chat uses OpenClaw's
[OpenAI-compatible API](https://docs.openclaw.ai/gateway/openai-http-api) (`model: openclaw/default`, streaming SSE). STT/TTS on 8081/8082 are your own
OpenAI-style servers; OpenClaw itself doesn't serve them.

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
