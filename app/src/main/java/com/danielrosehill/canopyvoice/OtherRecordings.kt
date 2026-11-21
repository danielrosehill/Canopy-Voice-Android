package com.danielrosehill.canopyvoice

import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether some other app is capturing audio right now. Android reports every active recording to every app
 * (other apps' entries anonymised, session ids kept), so anything whose session is not our wake word recorder
 * counts, except the system hotword source. The wake word loop yields the mic while [busy] is true.
 * Our own turn recording never overlaps: the wake loop only runs while the assistant is idle.
 */
class OtherRecordings(private val audio: AudioManager) : AutoCloseable {
    val busy = MutableStateFlow(false)

    /** The other recordings, for the log ("source 1 session 1234"). */
    @Volatile var describe: String = ""
        private set

    /** Session id of our own wake word AudioRecord while it runs, 0 otherwise. */
    @Volatile var ownSession: Int = 0
        set(v) { field = v; update(audio.activeRecordingConfigurations) }

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) = update(configs)
    }

    init {
        audio.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        update(audio.activeRecordingConfigurations)
    }

    private fun update(configs: List<AudioRecordingConfiguration>) {
        val own = ownSession
        val other = configs.filter { c -> c.clientAudioSessionId != own && c.clientAudioSource != HOTWORD_SOURCE }
        describe = other.joinToString { "source ${it.clientAudioSource} session ${it.clientAudioSessionId}" }
        busy.value = other.isNotEmpty()
    }

    override fun close() = audio.unregisterAudioRecordingCallback(callback)

    private companion object {
        /** MediaRecorder.AudioSource.HOTWORD (system API, not in the public SDK). */
        const val HOTWORD_SOURCE = 1999
    }
}
