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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.danielrosehill.canopyvoice.wakeword.MicroWakeWordDetector
import com.danielrosehill.canopyvoice.wakeword.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        Log.i(TAG, "listening for '${model.wakeWord}', cutoff ${settings.wakeCutoff.takeIf { it > 0f } ?: model.cutoff}")
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = ByteBuffer.allocateDirect(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        try {
            while (scope.isActive) {
                Assistant.phase.first { it == Phase.Idle }
                var heard = false
                Assistant.mic.withLock {
                    val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK_BYTES * 4))
                    try {
                        rec.startRecording()
                        detector.reset()
                        while (scope.isActive && Assistant.phase.value == Phase.Idle) {
                            chunk.clear()
                            val n = rec.read(chunk, CHUNK_BYTES)
                            if (n <= 0) continue
                            chunk.limit(n)
                            if (detector.detect(chunk)) { heard = true; break }
                            Assistant.wakeLevel.value = detector.lastAverage
                        }
                    } finally {
                        runCatching { rec.stop() }
                        rec.release()
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
            detector.close()
            Assistant.wakeRunning.value = false
        }
    }

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
        const val ACTION_PAUSE = "com.danielrosehill.canopyvoice.PAUSE_LISTENING"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, WakeWordService::class.java))
    }
}
