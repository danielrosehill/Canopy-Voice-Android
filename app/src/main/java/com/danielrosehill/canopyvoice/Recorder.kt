package com.danielrosehill.canopyvoice

import android.content.Context
import android.media.MediaRecorder
import kotlinx.coroutines.delay
import java.io.File

/**
 * Records one utterance to AAC/M4A and stops by itself on silence.
 * Endpointing is a simple amplitude gate: once speech has been heard, [silenceMs] of quiet ends the turn.
 */
class Recorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    @Volatile private var stopRequested = false

    val file: File get() = File(context.cacheDir, "turn.m4a")

    fun requestStop() { stopRequested = true }

    /** Returns the file if speech was heard, null if nothing was said before [noSpeechMs]. */
    suspend fun record(
        speechLevel: Int = 2500,
        silenceMs: Long = 1200,
        noSpeechMs: Long = 7000,
        maxMs: Long = 20000,
        onLevel: (Int) -> Unit = {},
    ): File? {
        stopRequested = false
        val out = file
        out.delete()
        val r = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(32000)
            setOutputFile(out.absolutePath)
            prepare()
            start()
        }
        recorder = r
        val started = System.currentTimeMillis()
        var heard = false
        var lastLoud = started
        try {
            while (!stopRequested) {
                delay(100)
                val now = System.currentTimeMillis()
                val level = r.maxAmplitude
                onLevel(level)
                if (level >= speechLevel) { heard = true; lastLoud = now }
                if (heard && now - lastLoud >= silenceMs) break
                if (!heard && now - started >= noSpeechMs) break
                if (now - started >= maxMs) break
            }
        } finally {
            runCatching { r.stop() }
            r.release()
            recorder = null
        }
        // A manual stop counts as "I said something".
        return if (heard || stopRequested) out else null
    }
}
