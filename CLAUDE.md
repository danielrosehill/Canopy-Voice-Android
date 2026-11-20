# Canopy-Voice-Android: agent context

Release tier: **small** (`v1.0`, `v1.1`, ...).

- Kotlin + Compose, package `com.danielrosehill.canopyvoice`. Server protocol: Canopy `docs/voice.md` and
  `voice/src/canopy_voice/server.py`.
- Never build on daniellaptop: push and let CI build. Release = annotated tag `v1.N`; CI attaches the APK and
  dispatches `danielrosehill/F-Droid-Household-Repo`.
- Signing key: `~/.config/android-signing/canopy-voice.jks` (+ `.pass`), backed up in 1Password vault
  `Development` ("Android signing key - Canopy Voice"). Losing it means every phone reinstalls.
- Generic open-source client: no server URLs, tokens or names in code; they are entered in the app.
