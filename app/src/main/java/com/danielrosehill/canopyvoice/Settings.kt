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

    /** Start listening as soon as the app (or the assistant gesture) opens it. */
    var listenOnOpen: Boolean
        get() = prefs.getBoolean("listen_on_open", true)
        set(v) = prefs.edit().putBoolean("listen_on_open", v).apply()

    /** Listen again after a reply that asks something (a question or a held action waiting for a yes). */
    var listenAfterQuestion: Boolean
        get() = prefs.getBoolean("listen_after_question", true)
        set(v) = prefs.edit().putBoolean("listen_after_question", v).apply()

    val configured: Boolean get() = serverUrl.isNotEmpty() && token.isNotEmpty()
}
