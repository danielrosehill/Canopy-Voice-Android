package com.danielrosehill.canopyvoice

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

enum class Phase { Idle, Listening, Thinking, Speaking }

/**
 * One assistant per process, shared by the screen, the wake word service and the system assist session.
 * [mic] is held by whoever is capturing: the wake word loop gives it up as soon as [phase] leaves Idle.
 */
object Assistant {
    val phase = MutableStateFlow(Phase.Idle)
    val level = MutableStateFlow(0)
    val said = MutableStateFlow("")
    val reply = MutableStateFlow("")
    val error = MutableStateFlow("")
    /** Smoothed wake word probability, for the readout in Settings. */
    val wakeLevel = MutableStateFlow(0f)
    val wakeRunning = MutableStateFlow(false)
    /** Mirrors [Settings.listeningPaused] for the screen; refreshed by [syncPaused]. */
    val paused = MutableStateFlow(false)

    val mic = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var recorder: Recorder? = null

    // Follow-ups: the server keeps a conversation for session_ttl_s (180 s by default); stay under it.
    private var conversationId: String? = null
    private var conversationAt = 0L
    private const val TTL_MS = 170_000L

    fun syncPaused(context: Context) { paused.value = Settings(context).listeningPaused }

    /**
     * The one listening on/off, used by the screen, the Quick Settings tile and the wake word notification.
     * Pausing stops the wake word service and any recording in progress; resuming starts the wake word service
     * again if it is switched on in Settings. Android only lets a microphone service start while the app is in
     * the foreground, so callers outside the open app (the tile) open [MainActivity] to resume it.
     */
    fun setPaused(context: Context, pause: Boolean) {
        val app = context.applicationContext
        val settings = Settings(app)
        settings.listeningPaused = pause
        paused.value = pause
        if (pause) {
            if (phase.value == Phase.Listening) cancel()
            WakeWordService.stop(app)
        } else if (settings.wakeWordEnabled && !wakeRunning.value) {
            runCatching { WakeWordService.start(app) }
        }
        runCatching { TileService.requestListeningState(app, ComponentName(app, ListeningTileService::class.java)) }
    }

    fun currentConversation(): String? =
        conversationId?.takeIf { System.currentTimeMillis() - conversationAt < TTL_MS }

    fun newConversation() { conversationId = null }

    fun stopRecording() { recorder?.requestStop() }

    fun cancel() {
        recorder?.requestStop()
        job?.cancel()
        job = null
        player?.release()
        player = null
        phase.value = Phase.Idle
    }

    fun listen(context: Context, chime: Boolean = false) {
        val app = context.applicationContext
        val settings = Settings(app)
        if (!settings.configured) { error.value = "Set the server and token in Settings"; return }
        cancel()
        error.value = ""
        phase.value = Phase.Listening // the wake word loop sees this and releases the mic
        job = scope.launch {
            val rec = Recorder(app).also { recorder = it }
            val audio = try {
                mic.withLock {
                    if (chime) beep()
                    rec.record(onLevel = { level.value = it })
                }
            } catch (e: Exception) {
                error.value = "Recording failed: ${e.message}"; phase.value = Phase.Idle; return@launch
            } finally {
                level.value = 0
                recorder = null
            }
            if (audio == null) { phase.value = Phase.Idle; return@launch }
            phase.value = Phase.Thinking
            val client = settings.client()
            handle(app, settings, runCatching { withContext(Dispatchers.IO) { client.audioTurn(audio, currentConversation()) } })
        }
    }

    fun sendText(context: Context, text: String) {
        val app = context.applicationContext
        val settings = Settings(app)
        if (!settings.configured) { error.value = "Set the server and token in Settings"; return }
        cancel()
        error.value = ""
        phase.value = Phase.Thinking
        job = scope.launch {
            val client = settings.client()
            handle(app, settings, runCatching { withContext(Dispatchers.IO) { client.textTurn(text, currentConversation()) } })
        }
    }

    private suspend fun handle(app: Context, settings: Settings, result: Result<TurnResult>) {
        val r = result.getOrElse { e ->
            error.value = if (e is VoiceError) e.message.orEmpty() else "Can't reach ${settings.serverUrl}: ${e.message}"
            phase.value = Phase.Idle
            return
        }
        said.value = r.transcript
        reply.value = r.reply + (r.held?.let { "\n\nWaiting for a yes: $it" } ?: "")
        if (r.speaker.isNotEmpty() && settings.clipSpeaker.isEmpty()) settings.clipSpeaker = r.speaker
        if (r.conversationId.isNotEmpty()) { conversationId = r.conversationId; conversationAt = System.currentTimeMillis() }
        r.wav?.let { phase.value = Phase.Speaking; play(app, it) }
        phase.value = Phase.Idle
        val asks = r.reply.trimEnd().endsWith("?") || r.held != null
        if (asks && settings.listenAfterQuestion && !settings.listeningPaused) listen(app)
    }

    private fun beep() {
        runCatching {
            val t = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            t.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            Thread.sleep(150)
            t.release()
        }
    }

    private suspend fun play(app: Context, wav: ByteArray) {
        val f = File(app.cacheDir, "reply.wav")
        withContext(Dispatchers.IO) { f.writeBytes(wav) }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        // Audio focus only while the reply plays (other audio ducks), released straight after; never held idle.
        val audio = app.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs).build()
        audio.requestAudioFocus(focus)
        try {
            suspendCancellableCoroutine { cont ->
                val p = MediaPlayer()
                player = p
                p.setAudioAttributes(attrs)
                p.setDataSource(f.absolutePath)
                p.setOnCompletionListener { it.release(); if (player === it) player = null; if (cont.isActive) cont.resume(Unit) }
                p.setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; if (cont.isActive) cont.resume(Unit); true }
                cont.invokeOnCancellation { runCatching { p.release() } }
                p.prepare()
                p.start()
            }
        } finally {
            audio.abandonAudioFocusRequest(focus)
        }
    }
}
