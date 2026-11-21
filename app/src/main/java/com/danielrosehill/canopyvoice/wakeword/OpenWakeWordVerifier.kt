// Port of the streaming feature pipeline of pyopen_wakeword 1.1.0 (OHF-Voice, Apache-2.0), openwakeword.py:
// OpenWakeWordFeatures.process_streaming + OpenWakeWord.process_streaming, same constants and windowing, so
// scores match the Jabra satellite's second-stage check (voice-satellite-deployment patches/wake_verify.py).
package com.danielrosehill.canopyvoice.wakeword

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Second-stage wake word check: scores a few seconds of audio with an openWakeWord model (default: the
 * bundled `hey_yossi_oww.tflite`, openWakeWord `hey_yossi` v1) and returns the peak probability.
 *
 * Each [score] call starts from the same state as a fresh pyopen_wakeword stream that has already been fed
 * [LEAD_SAMPLES] of silence (its 8 s autofill + 1 s lead, as in the offline tuning), snapshotted once at init.
 */
class OpenWakeWordVerifier(mel: ByteBuffer, emb: ByteBuffer, ww: ByteBuffer) : AutoCloseable {
    private val melI = Interpreter(mel).apply { resizeInput(0, intArrayOf(1, MEL_SAMPLES)); allocateTensors() }
    private val embI = Interpreter(emb).apply { resizeInput(0, intArrayOf(1, EMB_FEATURES, NUM_MELS, 1)); allocateTensors() }
    private val wwI = Interpreter(ww).apply { allocateTensors() }
    private val wwWindows = wwI.getInputTensor(0).shape()[1]

    private val melIn = direct(MEL_SAMPLES)
    private val melOut = direct(melI.getOutputTensor(0).numElements())
    private val embIn = direct(EMB_FEATURES * NUM_MELS)
    private val embOut = direct(embI.getOutputTensor(0).numElements())
    private val wwIn = direct(wwWindows * WW_FEATURES)
    private val wwOut = direct(wwI.getOutputTensor(0).numElements())

    private class State(
        val audio: FloatArray = FloatArray(MAX_SAMPLES),
        var newAudio: Int = AUTOFILL_SECONDS * RATE,
        val mels: FloatArray = FloatArray(MAX_MELS * NUM_MELS),
        var newMels: Int = 0,
        val embs: FloatArray = FloatArray(MAX_EMB * WW_FEATURES),
        var newEmb: Int = 0,
        var peak: Float = 0f,
    ) {
        fun copy() = State(audio.copyOf(), newAudio, mels.copyOf(), newMels, embs.copyOf(), newEmb, peak)
    }

    private val warm: State = State().also { s ->
        val zeros = ShortArray(CHUNK)
        var left = LEAD_SAMPLES
        while (left > 0) { val n = minOf(CHUNK, left); feed(s, zeros, 0, n); left -= n }
    }

    /** Peak probability over [pcm] (16 kHz mono PCM16). */
    @Synchronized
    fun score(pcm: ShortArray): Float {
        val s = warm.copy()
        var i = 0
        while (i < pcm.size) { val n = minOf(CHUNK, pcm.size - i); feed(s, pcm, i, n); i += n }
        return s.peak
    }

    private fun feed(s: State, src: ShortArray, off: Int, n: Int) {
        val a = s.audio
        System.arraycopy(a, n, a, 0, a.size - n)
        for (k in 0 until n) a[a.size - n + k] = src[off + k].toFloat()
        s.newAudio = minOf(a.size, s.newAudio + n)
        while (s.newAudio >= MEL_SAMPLES) {
            val start = a.size - s.newAudio
            melIn.clear(); melIn.put(a, start, MEL_SAMPLES); melIn.rewind()
            s.newAudio = maxOf(0, s.newAudio - SAMPLES_PER_CHUNK)
            melOut.clear()
            melI.run(melIn, melOut)
            val frames = melOut.capacity() / NUM_MELS
            val m = s.mels
            System.arraycopy(m, frames * NUM_MELS, m, 0, m.size - frames * NUM_MELS)
            melOut.rewind()
            for (k in 0 until frames * NUM_MELS) m[m.size - frames * NUM_MELS + k] = melOut.get() / 10f + 2f
            s.newMels = minOf(MAX_MELS, s.newMels + frames)
            while (s.newMels >= EMB_FEATURES) {
                val ms = (MAX_MELS - s.newMels) * NUM_MELS
                embIn.clear(); embIn.put(m, ms, EMB_FEATURES * NUM_MELS); embIn.rewind()
                s.newMels = maxOf(0, s.newMels - EMB_STEP)
                embOut.clear()
                embI.run(embIn, embOut)
                val ne = embOut.capacity() / WW_FEATURES
                val e = s.embs
                System.arraycopy(e, ne * WW_FEATURES, e, 0, e.size - ne * WW_FEATURES)
                embOut.rewind()
                for (k in 0 until ne * WW_FEATURES) e[e.size - ne * WW_FEATURES + k] = embOut.get()
                s.newEmb = minOf(MAX_EMB, s.newEmb + ne)
                while (s.newEmb >= wwWindows) {
                    val es = (MAX_EMB - s.newEmb) * WW_FEATURES
                    wwIn.clear(); wwIn.put(e, es, wwWindows * WW_FEATURES); wwIn.rewind()
                    s.newEmb = maxOf(0, s.newEmb - 1)
                    wwOut.clear()
                    wwI.run(wwIn, wwOut)
                    val p = wwOut.get(0)
                    if (p > s.peak) s.peak = p
                }
            }
        }
    }

    override fun close() {
        melI.close(); embI.close(); wwI.close()
    }

    companion object {
        const val RATE = 16000
        private const val AUTOFILL_SECONDS = 8
        private const val MAX_SAMPLES = 10 * RATE
        private const val SAMPLES_PER_CHUNK = 1280
        private const val MAX_MELS = 10 * 97
        private const val MEL_SAMPLES = 1760
        private const val NUM_MELS = 32
        private const val EMB_FEATURES = 76
        private const val EMB_STEP = 8
        private const val MAX_EMB = 10 * EMB_STEP
        private const val WW_FEATURES = 96
        private const val CHUNK = 1024
        /** 1 s of silence before the clip, as in the offline tuning (wake_verify.py LEAD_SILENCE). */
        private const val LEAD_SAMPLES = RATE

        private fun direct(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private fun asset(context: Context, name: String): ByteBuffer {
            val b = context.assets.open("wakeword/$name").use { it.readBytes() }
            return ByteBuffer.allocateDirect(b.size).order(ByteOrder.nativeOrder()).put(b).apply { rewind() }
        }

        fun bundled(context: Context) = OpenWakeWordVerifier(
            asset(context, "melspectrogram.tflite"), asset(context, "embedding_model.tflite"),
            asset(context, "hey_yossi_oww.tflite"),
        )
    }
}
