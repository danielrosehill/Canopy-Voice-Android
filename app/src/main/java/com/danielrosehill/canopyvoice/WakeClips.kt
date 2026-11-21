package com.danielrosehill.canopyvoice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.danielrosehill.canopyvoice.wakeword.OpenWakeWordVerifier
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Every wake that reaches the second-stage check, saved for retraining the wake word:
 *
 *   <app external files>/wake-clips/<YYYY-MM-DD>/<HHMMSS-mmm>_<accept|reject>_<score>_<speaker>.wav
 *   <app external files>/wake-clips/wakes.jsonl   one JSON object per wake (see [save])
 *
 * The folder is /sdcard/Android/data/com.danielrosehill.canopyvoice/files/wake-clips/ (adb pull works; Settings >
 * Advanced > Share wake clips zips it for any share target). 16 kHz mono PCM16 WAV, 3.3 s: 3 s before the
 * microWakeWord hit's post-roll end. Oldest clips are deleted past [MAX_CLIPS].
 */
object WakeClips {
    private const val TAG = "YossiWake"
    private const val MAX_CLIPS = 1000
    private val io = Executors.newSingleThreadExecutor()

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "wake-clips")

    fun count(context: Context): Int =
        dir(context).listFiles()?.filter { it.isDirectory }?.sumOf { d -> d.listFiles { f -> f.extension == "wav" }?.size ?: 0 } ?: 0

    fun save(context: Context, pcm: ShortArray, accepted: Boolean, score: Float, threshold: Float, mode: String,
             mww: Float, verifyMs: Long, speaker: String) {
        val app = context.applicationContext
        val now = Date()
        io.execute {
            runCatching {
                val root = dir(app)
                val day = File(root, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)).apply { mkdirs() }
                val label = speaker.lowercase(Locale.US).replace(Regex("[^a-z0-9-]"), "").ifEmpty { "unknown" }
                val name = "%s_%s_%.3f_%s.wav".format(Locale.US, SimpleDateFormat("HHmmss-SSS", Locale.US).format(now),
                    if (accepted) "accept" else "reject", score, label)
                val f = File(day, name)
                writeWav(f, pcm)
                val rec = JSONObject()
                    .put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(now))
                    .put("speaker", label)
                    .put("accepted", accepted)
                    .put("score", score.toDouble())
                    .put("threshold", threshold.toDouble())
                    .put("mode", mode)
                    .put("mww", mww.toDouble())
                    .put("verify_ms", verifyMs)
                    .put("clip", "${day.name}/$name")
                    .put("source", "android")
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("app_version", BuildConfigVersion.name(app))
                File(root, "wakes.jsonl").appendText(rec.toString() + "\n")
                prune(root)
            }.onFailure { Log.w(TAG, "could not save wake clip", it) }
        }
    }

    private fun prune(root: File) {
        val wavs = root.listFiles()?.filter { it.isDirectory }?.flatMap { d -> d.listFiles { f -> f.extension == "wav" }?.toList().orEmpty() }
            ?: return
        if (wavs.size <= MAX_CLIPS) return
        wavs.sortedBy { it.lastModified() }.take(wavs.size - MAX_CLIPS).forEach { it.delete() }
    }

    fun writeWav(f: File, pcm: ShortArray, rate: Int = 16000) {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size * 2)
        f.outputStream().use { it.write(h.array()); it.write(data.array()) }
    }

    /** Zips the whole folder into the cache, for sharing. */
    fun zip(context: Context, speaker: String): File {
        val root = dir(context)
        val label = speaker.lowercase(Locale.US).replace(Regex("[^a-z0-9-]"), "").ifEmpty { "unknown" }
        val out = File(context.cacheDir, "share/yossi-wake-clips-$label-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.zip")
        out.parentFile?.mkdirs()
        out.parentFile?.listFiles()?.forEach { if (it != out) it.delete() }
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry("wake-clips/" + f.relativeTo(root).path))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return out
    }

    /** Reads a 16 kHz mono PCM16 WAV (44-byte header assumed, as written by [writeWav] or ffmpeg -ac 1 -ar 16000). */
    fun readWav(f: File): ShortArray {
        RandomAccessFile(f, "r").use { r ->
            val bytes = ByteArray((r.length() - 44).toInt()); r.seek(44); r.readFully(bytes)
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return ShortArray(bytes.size / 2) { b.short }
        }
    }
}

/**
 * Debug hook for checking the second-stage scores on the phone, no UI: only callers holding DUMP (adb shell)
 * can send it.
 *   adb push clip.wav /sdcard/Android/data/com.danielrosehill.canopyvoice/files/score/
 *   adb shell am broadcast -n com.danielrosehill.canopyvoice/.ScoreWavReceiver
 *   adb logcat -s YossiWake   ->  "score <file>: 0.855 (12 ms)" for every *.wav in files/score/
 */
class ScoreWavReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val dir = File(context.getExternalFilesDir(null), "score")
                OpenWakeWordVerifier.bundled(context).use { v ->
                    dir.listFiles { f -> f.extension == "wav" }?.sortedBy { it.name }?.forEach { f ->
                        val t0 = System.currentTimeMillis()
                        val s = v.score(WakeClips.readWav(f))
                        Log.i("YossiWake", "score ${f.name}: %.4f (%d ms)".format(Locale.US, s, System.currentTimeMillis() - t0))
                    }
                }
            } catch (e: Exception) {
                Log.e("YossiWake", "score failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

object BuildConfigVersion {
    fun name(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
