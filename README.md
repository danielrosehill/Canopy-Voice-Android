# Yossi for Android

<img src="art/yossi-512.png" width="128" align="right" alt="Yossi the anteater">

**Yossi** (the anteater) is a thin Android client for [`canopy-voice`](https://github.com/danielrosehill/Canopy/blob/main/docs/voice.md),
the Pipecat voice front end of Canopy. Tap (or just open the app), speak, hear the reply. The server does
speech-to-text, the Claude tool loop over your Canopy tool group, and text-to-speech; the phone only records and
plays.

## Use

1. Install from the F-Droid repo you publish it to, or the APK on the [releases](../../releases) page.
2. Open it, tap **Settings**, enter the server (for example `http://10.0.0.2:8797`, or a tailnet address off the
   LAN) and your speaker token (`VOICE_TOKEN_<NAME>` in the server's `secrets.env`). Nothing personal is built in.
   For a server published through a Cloudflare tunnel behind **Cloudflare Access**, also enter a service token's
   `CF-Access-Client-Id` and `CF-Access-Client-Secret` (v1.2); the app then sends them on every request. Leave
   both empty on a LAN or tailnet.
3. Opening the app starts listening. It stops on its own after about 1.2 s of silence, or tap the button.

- **Listening on / paused (v1.3):** one switch for all hands-free listening, so you don't have to flip the
  separate settings. It is on the main screen under the title, as a Quick Settings tile ("Yossi": edit your
  Quick Settings panel and drag it in), and as **Pause listening** on the wake word notification. Paused means
  the app never opens the microphone by itself: no listening when opened, no listening again after a question,
  and the wake word service stops. **Talk**, typing and the assist gesture still work while paused (each is an
  explicit request). The choice survives restarts, and the separate settings are kept, so resuming brings back
  whatever they were. Resuming from the tile with the wake word on opens the app, because Android only lets a
  microphone service start while the app is in the foreground.

- **Widget (v1.5):** a 1x1 home screen widget (Settings > **Add the Yossi widget to the home screen**, or the
  launcher's widget list). One tap opens the chat thread and starts listening. It works with the wake word off and
  with listening paused, like **Talk** (`YossiWidget.kt`, `MainActivity.EXTRA_LISTEN`).
- **Wake word switch (v1.5):** on the main screen. Off stops only the wake word service (no microphone service, no
  notification); turning it on also lifts a pause. The listening on/off below moved to the bottom of Settings, and is still the tile.
- **Chat thread (v1.5):** the whole conversation is shown as bubbles. It is cleared when the server would have
  forgotten it (170 s after the last turn), on the next turn or the next time the app is opened by an intent. Kept in memory only.
  A rotation, or reopening from Recents, no longer repeats the launch request.

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

`Authorization: Bearer <speaker token>` on both, plus `CF-Access-Client-Id` / `CF-Access-Client-Secret` when
both are set (Access app with a Service Auth policy: a missing or wrong token gets a 403 from Cloudflare before the
request reaches the server). A 422 from the audio route means the server heard nothing.
The audio route's `reply=json` mode carries no audio (canopy-voice as of 2026-11-20), so voice turns use WAV
and `held` is only shown for typed turns.

Plain HTTP is allowed (`usesCleartextTraffic`), because the server normally sits on a LAN or tailnet.

## Wake word (v1.1)

"Hey Yossi" is detected **on the phone** with [microWakeWord](https://github.com/kahrendt/microWakeWord):
the bundled model is `app/src/main/assets/wakeword/hey_yossi.tflite` + `.json`, the micro model from
[`danielrosehill/Hey-Yossi-microWakeWord`](https://huggingface.co/danielrosehill/Hey-Yossi-microWakeWord)
(v1, trained 2026-10-29, CC BY-NC 4.0, cutoff 0.97, window 5). Only audio after a detection leaves the phone.

- Settings > **Wake word** starts `WakeWordService`, a microphone foreground service with a notification
  (**Pause listening** on the notification pauses all hands-free listening, see Use; the wake word setting itself
  stays on, so resuming restarts the service). It has to be switched on from the open app: Android refuses to start a
  microphone service from the background or at boot.
- Settings > **Advanced** holds the tuning: **Strictness** (probability cutoff, 0.80 to 0.995, with the live smoothed
  score beside it), the false-wake check, wake clips and the custom folder. The everyday settings stay short.
- **Custom wake word folder**: a folder with any micro-wake-word-models v2 manifest (`type: micro`) and its
  `.tflite`, for example an ESPHome model. Needs all-files access.
- Pipeline: `AudioRecord` 16 kHz PCM16 → TFLite Micro frontend (40 features per 10 ms, native, module
  `microfeatures`) → streaming model via LiteRT 1.4.1. Frontend and detector are adapted from
  [brownard/Ava](https://github.com/brownard/Ava) (Apache-2.0, commit `2c76f51`); see `microfeatures/README.md`.

## False wakes, sharing the mic, battery (v1.4, 2026-11-21)

Why: microWakeWord `hey_yossi` fired on Daniel's own continuous speech (dictation, reading aloud) on the Jabra
satellite, 3 times on 2026-11-21. A higher cutoff does not separate those from real wakes. The phone uses the same
model, so it gets the same safeguards as the satellite (`voice-satellite-deployment` `patches/wake_verify.py`, commit
`9fc2c24`).

**Second-stage check** (`wakeword/OpenWakeWordVerifier.kt`). When microWakeWord fires, the service reads 0.3 s more
audio. It then scores the last 3.3 s (ring buffer, `wakeword/PcmRing.kt`) with a second model that was trained
separately: openWakeWord `hey_yossi` v1 (`assets/wakeword/hey_yossi_oww.tflite`, from Wake-Words
`models/hey_yossi-v1/`, HF `danielrosehill/Hey-Yossi-openWakeWord`, sha256 `248752e5ba5197c8...`). It also uses
openWakeWord's `melspectrogram.tflite` and `embedding_model.tflite` (from pyopen_wakeword 1.1.0, Apache-2.0). The
code is a line-by-line port of pyopen_wakeword's streaming pipeline (8 s autofill, 1 s lead silence, snapshotted once
at start), so its scores match the satellite's.
- Settings > Advanced > **False-wake check**: On (`enforce`, the default; a wake below the threshold is dropped with
  no chime and no turn), Log only (`shadow`), Off. **Check threshold** defaults to 0.05.
- Tuning (offline, held-out real takes, see the satellite README): with the 0.3 s post-roll, 51 of 53 genuine takes
  pass 0.05. The 2026-11-21 false-wake recordings score 0.0009-0.0021.
- On the phone (2026-11-21, OnePlus CPH2493, Android 16, `ScoreWavReceiver`): the 7 genuine takes scored 0.718-0.886
  and the 7 false-wake recordings 0.0009-0.0014. These match pyopen_wakeword to 4 decimals. A check takes 0.2-0.5 s for
  a wake-length clip, run once per first-stage hit. Rejected wakes get 1 s of refractory.
- logcat `YossiWake`: `wake: check 0.855 (threshold 0.05, enforce) -> accept, 240 ms`.
- No chaining: the mic re-opens after a reply only if the reply ends with "?" or an action is held for a yes
  (Settings "Listen again after a question"). There is no always-on follow-up window like the one now turned off on the
  satellite.

**Wake clips for retraining** (`WakeClips.kt`). Every wake that reaches the check is saved, accepted or rejected:
`/sdcard/Android/data/com.danielrosehill.canopyvoice/files/wake-clips/<YYYY-MM-DD>/<HHMMSS-mmm>_<accept|reject>_<score>_<speaker>.wav`
(16 kHz mono PCM16, 3.3 s). Each clip also gets one JSON line in `wake-clips/wakes.jsonl`:
`{time, speaker, accepted, score, threshold, mode, mww, verify_ms, clip, source: "android", device, app_version}`.
Only the newest 1000 clips are kept. The speaker label is Settings > Advanced > **Speaker on clips**. If that is empty,
the app fills it from the `speaker` field that canopy-voice returns on a typed turn (audio turns don't carry it), and
until then the label is `unknown`. Ways to get the clips off the phone: **Share wake clips (N)** zips the folder and
opens the share sheet (FileProvider `<pkg>.files`, `cache/share/`), or
`adb pull /sdcard/Android/data/com.danielrosehill.canopyvoice/files/wake-clips`. canopy-voice has no upload
endpoint, so there is no automatic upload.

**Sharing the microphone** (`OtherRecordings.kt`). `AudioManager.registerAudioRecordingCallback` reports every active
recording on the device. Other apps' entries are anonymised, but their session ids are kept. While any recording that
is not our wake word `AudioRecord` is active (the system `HOTWORD` source excepted), the wake loop releases the mic
and waits. It resumes when that recording ends. The wake loop also calls `setPreferredDevice(TYPE_BUILTIN_MIC)`, so a
plugged-in USB mic is left to the apps that want it. Turns (Talk, wake) still record from the system default input.
On 2026-11-21 the phone's `dumpsys audio` showed no always-on recorders (only the dictation app and the camera,
briefly), so the wake word is not blocked permanently. If it stays silent, look for `another app is recording
(source N session M)` in logcat. Audio focus: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` is requested only while a reply
plays and abandoned straight after. Nothing holds focus while idle.

**Battery.** The app holds no wake lock (the `WAKE_LOCK` permission is unused). A blocking `AudioRecord.read` of 40 ms
chunks in a microphone foreground service keeps the loop running with the screen off. That is deliberate: hands-free
must work with the phone locked, so listening does not stop at screen-off; **Pause listening** is the off switch. Each
10 ms frame runs one small microWakeWord inference. The second model only runs after a first-stage hit. A failing
`read()` now backs off (200 ms, then rebuilds the recorder) instead of spinning. While another app records, the
service records nothing at all.

**Debug hook** (adb only, `ScoreWavReceiver`, requires `android.permission.DUMP`):
`adb push clip.wav /sdcard/Android/data/com.danielrosehill.canopyvoice/files/score/`, then
`adb shell am broadcast -n com.danielrosehill.canopyvoice/.ScoreWavReceiver`, then read `adb logcat -s YossiWake`
(`score <file>: 0.8505 (364 ms)`). It expects 16 kHz mono WAV with a 44-byte header (`ffmpeg -ac 1 -ar 16000`).

## Digital assistant

The app implements Android's assistant framework (`VoiceInteractionService`, session service, a stub
recognition service), so it can be chosen in Settings > Apps > Default apps > **Digital assistant app**. The
assist gesture (long-press power or home, corner swipe) then opens Yossi listening, also from the lock screen.

## Permissions

Distributed through F-Droid, so it asks for what it can use. Each has a Grant row in Settings.

| Permission | Why |
|---|---|
| Microphone | turns and the wake word |
| Notifications | the wake word service's status notification |
| Display over other apps | lets a wake word bring Yossi's screen up from the background |
| All files access | custom wake word folder anywhere on the phone |
| Unrestricted battery | stops the system killing the wake word service |

## Not yet

- Streaming (Pipecat WebRTC/WebSocket transports), barge-in.

## Build and release

CI builds every push (`.github/workflows/build.yml`, runner `ubuntu-24.04`, no local builds). A tag `v*` makes a
GitHub release with the signed APK and dispatches the Rosehill Household F-Droid repo to publish it.
`versionCode` is the workflow run number. Tags follow `v1.0`, `v1.1`, ... (small project).

Signing secrets on the repo: `SIGNING_KEYSTORE_B64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD`; `FDROID_DISPATCH_TOKEN` for the dispatch. Keep the keystore: Android refuses an update
signed by another key.

License: MIT.
