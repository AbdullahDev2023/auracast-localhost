package com.akdevelopers.auracast.audio

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue

/**
 * Mixes two independent 16-bit mono PCM byte streams — mic (outgoing voice) and
 * earpiece (incoming caller) — into a single mixed stream.
 *
 * Each source pushes frames independently via [pushMicFrame] / [pushEarpieceFrame].
 * When both queues have a frame the two PCM buffers are additively mixed, clamped
 * to the 16-bit range, and delivered via [onMixedFrame].
 *
 * Drift handling: if one source gets more than [MAX_QUEUE_FRAMES] frames ahead the
 * oldest frame is dropped so the sources stay temporally aligned.
 */
class AudioCallMixer(
    private val frameBytes: Int,
    private val onMixedFrame: (ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "AC_CallMixer"
        private const val MAX_QUEUE_FRAMES = 10   // ~200 ms at 20 ms frames
    }

    private val micQueue      = ArrayBlockingQueue<ByteArray>(MAX_QUEUE_FRAMES)
    private val earpieceQueue = ArrayBlockingQueue<ByteArray>(MAX_QUEUE_FRAMES)

    /** Push a mic (outgoing) PCM frame. Thread-safe. */
    fun pushMicFrame(frame: ByteArray) {
        if (!micQueue.offer(frame)) {
            micQueue.poll()   // drop oldest to make room
            micQueue.offer(frame)
            Log.v(TAG, "mic queue overflow — dropped oldest frame")
        }
        tryMix()
    }

    /** Push an earpiece (incoming) PCM frame. Thread-safe. */
    fun pushEarpieceFrame(frame: ByteArray) {
        if (!earpieceQueue.offer(frame)) {
            earpieceQueue.poll()
            earpieceQueue.offer(frame)
            Log.v(TAG, "earpiece queue overflow — dropped oldest frame")
        }
        tryMix()
    }

    private fun tryMix() {
        while (micQueue.isNotEmpty() && earpieceQueue.isNotEmpty()) {
            val mic      = micQueue.poll()      ?: break
            val earpiece = earpieceQueue.poll() ?: break
            onMixedFrame(mix(mic, earpiece))
        }
    }

    private fun mix(a: ByteArray, b: ByteArray): ByteArray {
        val samples = minOf(a.size, b.size) / 2
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val sA    = readShortLE(a, i)
            val sB    = readShortLE(b, i)
            val mixed = (sA + sB).coerceIn(-32768, 32767).toShort()
            writeShortLE(out, i, mixed)
        }
        return out
    }

    /** Discard all buffered frames — call on stop/reset. */
    fun flush() {
        micQueue.clear()
        earpieceQueue.clear()
    }

    private fun readShortLE(buf: ByteArray, index: Int): Int {
        val lo = buf[index * 2].toInt() and 0xFF
        val hi = buf[index * 2 + 1].toInt()
        return ((hi shl 8) or lo).toShort().toInt()
    }

    private fun writeShortLE(buf: ByteArray, index: Int, value: Short) {
        buf[index * 2]     = (value.toInt() and 0xFF).toByte()
        buf[index * 2 + 1] = ((value.toInt() ushr 8) and 0xFF).toByte()
    }
}
