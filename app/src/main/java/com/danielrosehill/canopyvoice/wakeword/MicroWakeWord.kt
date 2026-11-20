// Adapted from brownard/Ava (Apache-2.0), app/src/main/java/com/example/ava/wakewords/microwakeword/
// (MicroWakeWordDetector, MicroWakeWordModel, TensorBuffer) and utils/BufferUtils.kt, commit 2c76f51.
// Changes: one model per detector, Timber removed, probability exposed for the debug readout.
package com.danielrosehill.canopyvoice.wakeword

import com.example.microfeatures.MicroFrontend
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

private const val BYTES_PER_CHUNK = 160 * 2 // 10 ms of 16 kHz 16-bit mono

/** Feeds 16 kHz PCM16 audio through the microWakeWord frontend and one streaming model. */
class MicroWakeWordDetector(
    model: ByteBuffer,
    private val probabilityCutoff: Float,
    private val slidingWindowSize: Int,
) : AutoCloseable {
    private val frontend = MicroFrontend()
    private val buffer = ByteBuffer.allocateDirect(BYTES_PER_CHUNK)
    private val interpreter = Interpreter(model)
    private val input: TensorBuffer
    private val outputScale: Float
    private val outputZeroPoint: Int
    private val probabilities = ArrayDeque<Float>(slidingWindowSize)

    /** Last smoothed probability, for a level readout. */
    @Volatile var lastAverage = 0f
        private set

    init {
        interpreter.allocateTensors()
        val inT = interpreter.getInputTensor(0)
        val q = inT.quantizationParams()
        input = TensorBuffer.create(inT.dataType(), inT.shape(), q.scale, q.zeroPoint)
        val outQ = interpreter.getOutputTensor(0).quantizationParams()
        outputScale = outQ.scale
        outputZeroPoint = outQ.zeroPoint
    }

    /** Returns true if the wake word was detected somewhere in [audio] (a direct or heap buffer of PCM16 LE). */
    fun detect(audio: ByteBuffer): Boolean {
        var hit = false
        buffer.fillFrom(audio)
        while (buffer.flip().remaining() == BYTES_PER_CHUNK) {
            val out = frontend.processSamples(buffer)
            buffer.position(buffer.position() + out.samplesRead * 2)
            buffer.compact()
            buffer.fillFrom(audio)
            if (out.features.isEmpty()) continue
            if (process(out.features)) hit = true
        }
        buffer.compact()
        return hit
    }

    fun reset() {
        probabilities.clear()
        input.clear()
        lastAverage = 0f
    }

    private fun process(features: FloatArray): Boolean {
        input.put(features)
        if (!input.isComplete) return false
        val output = Array(1) { ByteArray(1) }
        interpreter.run(input.getTensor(), output)
        input.clear()
        val p = (output[0][0].toUByte().toFloat() - outputZeroPoint) * outputScale
        if (probabilities.size == slidingWindowSize) probabilities.removeFirst()
        probabilities.add(p)
        lastAverage = probabilities.average().toFloat()
        return probabilities.size == slidingWindowSize && lastAverage > probabilityCutoff
    }

    override fun close() {
        frontend.close()
        interpreter.close()
    }
}

private fun ByteBuffer.fillFrom(src: ByteBuffer): Int {
    val remaining = remaining()
    if (remaining == 0) return 0
    val srcRemaining = src.remaining()
    return if (srcRemaining <= remaining) {
        put(src); srcRemaining
    } else {
        val limit = src.limit()
        src.limit(src.position() + remaining)
        put(src)
        src.limit(limit)
        remaining
    }
}

private abstract class TensorBuffer(dataType: DataType, shape: IntArray, val scale: Float, val zeroPoint: Int) {
    private val flatSize = shape.reduce { a, i -> a * i }
    protected val buffer: ByteBuffer = ByteBuffer.allocateDirect(flatSize * dataType.byteSize()).order(ByteOrder.nativeOrder())
    val isComplete get() = !buffer.hasRemaining()
    abstract fun put(src: FloatArray)
    fun getTensor(): ByteBuffer = buffer.duplicate().order(ByteOrder.nativeOrder()).apply { flip() }
    fun clear() { buffer.clear() }
    fun quantize(v: Float) = (v / scale) + zeroPoint

    companion object {
        fun create(dataType: DataType, shape: IntArray, scale: Float, zeroPoint: Int): TensorBuffer = when (dataType) {
            DataType.FLOAT32 -> object : TensorBuffer(DataType.FLOAT32, shape, scale, zeroPoint) {
                override fun put(src: FloatArray) { for (v in src) buffer.putFloat(quantize(v)) }
            }
            DataType.UINT8, DataType.INT8 -> object : TensorBuffer(DataType.UINT8, shape, scale, zeroPoint) {
                override fun put(src: FloatArray) { for (v in src) buffer.put(quantize(v).roundToInt().toByte()) }
            }
            else -> throw IllegalArgumentException("Unsupported tensor type $dataType")
        }
    }
}
