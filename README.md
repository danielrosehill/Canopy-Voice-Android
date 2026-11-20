# Canopy Voice for Android

A thin Android client for [`canopy-voice`](https://github.com/danielrosehill/Canopy/blob/main/docs/voice.md),
the Pipecat voice front end of Canopy. Tap (or just open the app), speak, hear the reply. The server does
speech-to-text, the Claude tool loop over your Canopy tool group, and text-to-speech; the phone only records and
plays.

## Use

1. Install from the F-Droid repo you publish it to, or the APK on the [releases](../../releases) page.
2. Open it, tap **Settings**, enter the server (for example `http://10.0.0.2:8797`, or a tailnet address off the
   LAN) and your speaker token (`VOICE_TOKEN_<NAME>` in the server's `secrets.env`). Nothing personal is built in.
3. Opening the app starts listening. It stops on its own after about 1.2 s of silence, or tap the button.

- **Follow-ups:** the app sends back `conversation_id` for 170 s, under the server's `session_ttl_s` (180 s), so
  "and tomorrow?" works. **New conversation** drops it.
- **Questions and confirmations:** when a reply ends in a question (canopy-voice reads a held action back and asks
  for a yes), the app listens again by itself. Switch this off in Settings.
- **Typing:** the text box sends a `POST /v1/turn` with `speak: true`.
- The app also answers the `ASSIST` and `VOICE_COMMAND` intents (some launchers and headset buttons send these).

## How it talks to the server

| Turn | Request | Reply used |
|---|---|---|
| Voice | `POST /v1/turn/audio`, multipart `audio` (AAC/M4A, 16 kHz mono), `conversation_id`?, `reply=wav` | WAV body; `X-Transcript`, `X-Reply-Text`, `X-Conversation-Id` headers (URL-encoded) |
| Text | `POST /v1/turn` JSON `{text, conversation_id?, speak: true}` | `reply`, `conversation_id`, `held`, `audio_b64` |

`Authorization: Bearer <speaker token>` on both. A 422 from the audio route means the server heard nothing.
The audio route's `reply=json` mode carries no audio (canopy-voice as of 2026-11-20), so voice turns use WAV
and `held` is only shown for typed turns.

Plain HTTP is allowed (`usesCleartextTraffic`), because the server normally sits on a LAN or tailnet.

## Not yet

- On-device wake word (openWakeWord ONNX, e.g. `hey_yossi`). v1.0 is tap or open to talk.
- Streaming (Pipecat WebRTC/WebSocket transports), barge-in.

## Build and release

CI builds every push (`.github/workflows/build.yml`, runner `ubuntu-24.04`, no local builds). A tag `v*` makes a
GitHub release with the signed APK and dispatches the Rosehill Household F-Droid repo to publish it.
`versionCode` is the workflow run number. Tags follow `v1.0`, `v1.1`, ... (small project).

Signing secrets on the repo: `SIGNING_KEYSTORE_B64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD`; `FDROID_DISPATCH_TOKEN` for the dispatch. Keep the keystore: Android refuses an update
signed by another key.

License: MIT.
