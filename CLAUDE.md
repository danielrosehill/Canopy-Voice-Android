# Canopy-Voice-Android: agent context

Release tier: **small** (`v1.0`, `v1.1`, ...).

- App name **Yossi** (anteater mascot, `art/yossi.svg`); Kotlin + Compose, package `com.danielrosehill.canopyvoice`. Server protocol: Canopy `docs/voice.md` and
  `voice/src/canopy_voice/server.py`.
- Never build on daniellaptop: push and let CI build. Release = annotated tag `v1.N`; CI attaches the APK and
  dispatches `danielrosehill/F-Droid-Household-Repo`.
- Signing key: `~/.config/android-signing/canopy-voice.jks` (+ `.pass`), backed up in 1Password vault
  `Development` ("Android signing key - Canopy Voice"). Losing it means every phone reinstalls.
- Generic open-source client: no server URLs, tokens or names in code; they are entered in the app.
- Wake word: `WakeWordService` + `wakeword/` (microWakeWord, adapted from brownard/Ava) + module `microfeatures`
  (native frontend, vendored). Package id stays `com.danielrosehill.canopyvoice` (changing it means reinstalling).
- False-wake check, wake clips, mic yielding, battery choices (v1.4): README "False wakes, sharing the mic,
  battery". Check scores on the phone with `ScoreWavReceiver` (adb, no UI), not by driving the app.
- The phone on adb is `10.0.0.52:5555` (OnePlus CPH2493, Android 16, Daniel's own phone). Don't drive its UI
  while he's using it; logcat tag `YossiWake`.
