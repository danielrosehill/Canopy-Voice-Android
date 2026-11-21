package com.danielrosehill.canopyvoice

import android.content.Context

/** Everything personal (server, token) is entered on the phone; nothing is baked into the APK. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("canopy_voice", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(v) = prefs.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v.trim()).apply()

    /** Optional Cloudflare Access service token, for a server published through a Cloudflare tunnel. */
    var cfClientId: String
        get() = prefs.getString("cf_client_id", "") ?: ""
        set(v) = prefs.edit().putString("cf_client_id", v.trim()).apply()

    var cfClientSecret: String
        get() = prefs.getString("cf_client_secret", "") ?: ""
        set(v) = prefs.edit().putString("cf_client_secret", v.trim()).apply()

    fun client() = VoiceClient(serverUrl, token, cfClientId, cfClientSecret)

    /** Start listening as soon as the app (or the assistant gesture) opens it. */
    var listenOnOpen: Boolean
        get() = prefs.getBoolean("listen_on_open", true)
        set(v) = prefs.edit().putBoolean("listen_on_open", v).apply()

    /** Listen again after a reply that asks something (a question or a held action waiting for a yes). */
    var listenAfterQuestion: Boolean
        get() = prefs.getBoolean("listen_after_question", true)
        set(v) = prefs.edit().putBoolean("listen_after_question", v).apply()

    /**
     * Master off switch for hands-free listening: while true the app never starts the microphone by itself
     * (no listen-on-open, no listening again after a question, no wake word service). Tapping Talk, typing and
     * the assist gesture still work. The individual settings below are left as they are, so resuming restores them.
     */
    var listeningPaused: Boolean
        get() = prefs.getBoolean("listening_paused", false)
        set(v) = prefs.edit().putBoolean("listening_paused", v).apply()

    /** Listen for the wake word in a foreground service. */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_word", false)
        set(v) = prefs.edit().putBoolean("wake_word", v).apply()

    /** Probability cutoff; 0 means use the model's own (0.97 for Hey Yossi). Higher is stricter. */
    var wakeCutoff: Float
        get() = prefs.getFloat("wake_cutoff", 0f)
        set(v) = prefs.edit().putFloat("wake_cutoff", v).apply()

    /** Optional folder holding a custom microWakeWord model (`*.json` + `.tflite`); needs all-files access. */
    var wakeModelFolder: String
        get() = prefs.getString("wake_folder", "") ?: ""
        set(v) = prefs.edit().putString("wake_folder", v.trim()).apply()

    val configured: Boolean get() = serverUrl.isNotEmpty() && token.isNotEmpty()
}
