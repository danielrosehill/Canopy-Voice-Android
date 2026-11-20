package com.danielrosehill.canopyvoice.wakeword

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A microWakeWord model in the micro-wake-word-models v2 layout: `<name>.json` with `type: micro`, `wake_word`,
 * `model`, `micro.probability_cutoff`, `micro.sliding_window_size`, next to the `.tflite`.
 * The bundled one is "Hey Yossi" (assets/wakeword/); a custom one can be picked from a folder on the phone.
 */
class WakeWordModel(val wakeWord: String, val tflite: ByteArray, val cutoff: Float, val window: Int) {

    fun detector(cutoffOverride: Float? = null): MicroWakeWordDetector {
        val buf = ByteBuffer.allocateDirect(tflite.size).order(ByteOrder.nativeOrder()).put(tflite).apply { rewind() }
        return MicroWakeWordDetector(buf, cutoffOverride ?: cutoff, window)
    }

    companion object {
        private fun parse(json: String, load: (String) -> ByteArray): WakeWordModel {
            val j = JSONObject(json)
            require(j.optString("type") == "micro") { "not a microWakeWord manifest" }
            val micro = j.getJSONObject("micro")
            return WakeWordModel(
                wakeWord = j.optString("wake_word", "wake word"),
                tflite = load(j.getString("model")),
                cutoff = micro.optDouble("probability_cutoff", 0.97).toFloat(),
                window = micro.optInt("sliding_window_size", 5),
            )
        }

        fun bundled(context: Context): WakeWordModel {
            val json = context.assets.open("wakeword/hey_yossi.json").bufferedReader().readText()
            return parse(json) { name -> context.assets.open("wakeword/$name").readBytes() }
        }

        /** First `*.json` manifest of type micro in [dir]. */
        fun fromFolder(dir: File): WakeWordModel? = dir.listFiles { f -> f.extension == "json" }?.firstNotNullOfOrNull { f ->
            runCatching { parse(f.readText()) { name -> File(dir, name).readBytes() } }.getOrNull()
        }
    }
}
