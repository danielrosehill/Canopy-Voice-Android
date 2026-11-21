package com.danielrosehill.canopyvoice.wakeword

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The last [capacity] samples of 16-bit mono audio. */
class PcmRing(private val capacity: Int) {
    private val buf = ShortArray(capacity)
    private var pos = 0
    private var filled = 0

    fun clear() { pos = 0; filled = 0 }

    /** Appends PCM16 LE from [src] (its position..limit). */
    fun write(src: ByteBuffer) {
        val s = src.order(ByteOrder.LITTLE_ENDIAN)
        while (s.remaining() >= 2) {
            buf[pos] = s.short
            pos = (pos + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    /** Oldest first. */
    fun snapshot(): ShortArray {
        val out = ShortArray(filled)
        val start = (pos - filled + capacity) % capacity
        for (i in 0 until filled) out[i] = buf[(start + i) % capacity]
        return out
    }
}
