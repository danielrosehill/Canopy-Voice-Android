package com.danielrosehill.canopyvoice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.danielrosehill.canopyvoice.wakeword.MicroWakeWordDetector
import com.danielrosehill.canopyvoice.wakeword.OpenWakeWordVerifier
import com.danielrosehill.canopyvoice.wakeword.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Listens for the wake word on the phone (microWakeWord, 16 kHz PCM16) in a microphone foreground service.
 * Only audio after a detection leaves the phone. While a turn runs, the microphone is handed to [Assistant].
 *
 * False wakes (v1.4): a microWakeWord hit is held for 0.3 s more audio, then the last 3.3 s are scored by a second,
 * independently trained model ([OpenWakeWordVerifier], openWakeWord `hey_yossi` v1); below
 * [Settings.verifyThreshold] the wake is dropped (mode enforce) or only logged (shadow). Each checked wake is saved
 * by [WakeClips] for retraining.
 *
 * Sharing the mic (v1.4): while any other app records ([OtherRecordings]), the loop releases its AudioRecord and
 * waits; it records from the built-in mic only, so a USB mic stays free for other apps. No wake lock: the blocking
 * AudioRecord.read in a microphone foreground service keeps the loop running with the screen off.
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            // The notification's Pause: the same master switch as the screen and the Quick Settings tile.
            Assistant.setPaused(this, true)
            stopSelf()
            return START_NOT_STICKY
        }
        val settings = Settings(this)
        if (settings.listeningPaused || !settings.wakeWordEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, notification(), type) else startForeground(NOTE_ID, notification())
        } catch (e: Exception) {
            // Android refuses a microphone service started from the background (e.g. a sticky restart).
            Log.w(TAG, "cannot start in foreground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        // onStartCommand runs on the main thread, so this check cannot race a second start.
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Assistant.wakeRunning.value = false
        Assistant.wakeLevel.value = 0f
        super.onDestroy()
    }

    private fun loadModel(settings: Settings): WakeWordModel {
        val folder = settings.wakeModelFolder
        if (folder.isNotEmpty()) {
            runCatching { WakeWordModel.fromFolder(File(folder)) }.getOrNull()?.let { return it }
            Log.w(TAG, "no usable model in $folder, using the bundled one")
        }
        return WakeWordModel.bundled(this)
    }

    @SuppressLint("MissingPermission")
    private suspend fun run() {
        Assistant.wakeRunning.value = true
        val settings = Settings(this)
        val model = loadModel(settings)
        val detector: MicroWakeWordDetector = model.detector(settings.wakeCutoff.takeIf { it > 0f })
        val mode = settings.verifyMode
        val threshold = settings.verifyThreshold
        val verifier = if (mode == Settings.VERIFY_OFF) null
            else runCatching { OpenWakeWordVerifier.bundled(this) }
                .onFailure { Log.e(TAG, "second-stage check unavailable, wakes go through unchecked", it) }.getOrNull()
        Log.i(TAG, "listening for '${model.wakeWord}', cutoff ${settings.wakeCutoff.takeIf { it > 0f } ?: model.cutoff}, " +
            "check $mode${if (verifier != null) " >= $threshold" else " (none)"}")
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = ByteBuffer.allocateDirect(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val ring = PcmRing(RING_SAMPLES)
        val audio = getSystemService(AudioManager::class.java)
        val others = OtherRecordings(audio)
        try {
            while (scope.isActive) {
                Assistant.phase.first { it == Phase.Idle }
                if (others.busy.value) {
                    // Another app is recording (e.g. a voice clip on a USB mic): stay off the microphone entirely.
                    Log.i(TAG, "another app is recording (${others.describe}); wake word paused")
                    Assistant.wakeLevel.value = 0f
                    others.busy.first { !it }
                    Log.i(TAG, "other recording ended; wake word resumes")
                    continue
                }
                var heard = false
                Assistant.mic.withLock {
                    val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK_BYTES * 4))
                    // Wake listening stays on the phone's own mic; a USB mic is left to the apps that want it.
                    builtInMic(audio)?.let { rec.setPreferredDevice(it) }
                    // Before startRecording, so our own recording is never mistaken for another app's.
                    others.ownSession = rec.audioSessionId
                    try {
                        rec.startRecording()
                        detector.reset()
                        ring.clear()
                        var post = -1        // samples of post-roll still to read after a first-stage hit, -1 = none
                        var ignore = 0       // samples to ignore after a rejected wake (refractory)
                        var mwwAtHit = 0f
                        var errors = 0
                        while (scope.isActive && Assistant.phase.value == Phase.Idle) {
                            if (others.busy.value) break // yield the mic; the outer loop waits
                            chunk.clear()
                            val n = rec.read(chunk, CHUNK_BYTES)
                            if (n <= 0) {
                                // Dead or invalidated recorder: back off instead of spinning, then rebuild it.
                                Log.w(TAG, "AudioRecord.read returned $n")
                                if (++errors >= 5) break
                                delay(200); continue
                            }
                            errors = 0
                            chunk.limit(n)
                            ring.write(chunk.duplicate().order(ByteOrder.LITTLE_ENDIAN))
                            val samples = n / 2
                            if (post >= 0) {
                                post -= samples
                                if (post <= 0) {
                                    post = -1
                                    if (verifyWake(verifier!!, ring, mode, threshold, mwwAtHit, settings)) { heard = true; break }
                                    detector.reset(); ignore = REFRACTORY_SAMPLES
                                }
                                continue
                            }
                            val hit = detector.detect(chunk)
                            Assistant.wakeLevel.value = detector.lastAverage
                            if (ignore > 0) { ignore -= samples; continue }
                            if (hit) {
                                mwwAtHit = detector.lastAverage
                                if (verifier == null) { heard = true; break }
                                post = POST_SAMPLES
                            }
                        }
                    } finally {
                        runCatching { rec.stop() }
                        rec.release()
                        others.ownSession = 0
                    }
                }
                if (heard) {
                    Log.i(TAG, "wake word detected")
                    withContext(Dispatchers.Main) { onWake() }
                    // Wait for the turn to start, so the loop does not grab the mic straight back.
                    Assistant.phase.first { it != Phase.Idle }
                }
            }
        } finally {
            others.close()
            detector.close()
            verifier?.close()
            Assistant.wakeRunning.value = false
        }
    }

    /** Second stage: score the last few seconds; true = let the wake through. Saves the clip either way. */
    private fun verifyWake(verifier: OpenWakeWordVerifier, ring: PcmRing, mode: String, threshold: Float, mww: Float,
                      settings: Settings): Boolean {
        val pcm = ring.snapshot()
        val t0 = SystemClock.elapsedRealtime()
        val score = runCatching { verifier.score(pcm) }.getOrElse {
            Log.e(TAG, "second-stage check failed; letting the wake through", it); return true
        }
        val ms = SystemClock.elapsedRealtime() - t0
        val ok = score >= threshold
        Log.i(TAG, "wake: check %.3f (threshold %.2f, %s) -> %s, %d ms".format(score, threshold, mode,
            if (ok) "accept" else if (mode == Settings.VERIFY_ENFORCE) "REJECT" else "would reject", ms))
        if (settings.saveWakeClips) WakeClips.save(this, pcm, ok, score, threshold, mode, mww, ms, settings.clipSpeakerLabel)
        return ok || mode != Settings.VERIFY_ENFORCE
    }

    private fun builtInMic(audio: AudioManager): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    private fun onWake() {
        Assistant.listen(this, chime = true)
        // With "display over other apps" granted, Android lets us bring the screen up from the background.
        if (android.provider.Settings.canDrawOverlays(this)) {
            runCatching {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_FROM_WAKE, true))
            }
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_yossi)
            .setContentTitle("Listening for “Hey Yossi”")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Pause listening", pause).build())
            .build()
    }

    companion object {
        private const val TAG = "YossiWake"
        private const val CHANNEL = "wake"
        private const val NOTE_ID = 1
        private const val RATE = 16000
        private const val CHUNK_BYTES = 1280 // 40 ms
        private const val POST_SAMPLES = RATE * 3 / 10        // 0.3 s more audio after microWakeWord fires
        private const val RING_SAMPLES = RATE * 33 / 10       // last 3 s + the post-roll
        private const val REFRACTORY_SAMPLES = RATE           // 1 s without first-stage hits after a rejection
        const val ACTION_PAUSE = "com.danielrosehill.canopyvoice.PAUSE_LISTENING"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, WakeWordService::class.java))
    }
}
