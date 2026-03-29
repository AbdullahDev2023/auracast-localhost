package com.akdevelopers.auracast.audio
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Feed-forward RMS dynamic-range compressor applied to 16-bit mono PCM frames.
 *
 * Signal chain: PCM in → RMS measure → gain compute → apply gain → PCM out
 *
 * Settings tuned for HIGH_QUALITY transparent audio (minimal coloration):
 *   - Threshold: –6 dBFS   — only clips peaks, leaves most signal untouched
 *   - Ratio:     1.5 : 1   — extremely gentle — barely audible gain reduction
 *   - Attack:    20 ms     — preserves transients / natural dynamics
 *   - Release:   300 ms    — smooth, inaudible gain recovery
 *   - Make-up:  +0 dB     — no makeup gain — preserves original loudness
 *
 * This acts more as a transparent peak limiter than a compressor, protecting
 * against digital clipping while leaving audio quality untouched.
 */
class AudioCompressor(
    sampleRate: Int,
    thresholdDb: Float = -6f,
    val ratio: Float       = 1.5f,
    attackMs: Float        = 20f,
    releaseMs: Float       = 300f,
    makeupGainDb: Float    = 0f,
) {
    private val threshold   = dbToLinear(thresholdDb)  // linear amplitude
    private val makeupGain  = dbToLinear(makeupGainDb)

    // Per-sample smoothing coefficients
    private val attackCoef  = timeCoef(attackMs,  sampleRate)
    private val releaseCoef = timeCoef(releaseMs, sampleRate)

    // State
    private var envelope = 0f   // running RMS envelope
    private var gain     = 1f   // current gain reduction factor

    /**
     * Compress [buffer] in-place. Buffer is 16-bit little-endian mono PCM.
     * Returns the same [buffer] reference for convenience.
     */
    fun process(buffer: ByteArray): ByteArray {
        val samples = buffer.size / 2
        for (i in 0 until samples) {
            val lo = buffer[i * 2].toInt() and 0xFF
            val hi = buffer[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toFloat()

            // Envelope follower (peak-smoothed)
            val absVal = abs(sample) / 32768f
            envelope = if (absVal > envelope)
                attackCoef  * envelope + (1f - attackCoef)  * absVal
            else
                releaseCoef * envelope + (1f - releaseCoef) * absVal

            // Gain computer
            val targetGain = if (envelope > threshold) {
                // How many dB over threshold?
                val overDb = linearToDb(envelope) - linearToDb(threshold)
                val reducedDb = overDb * (1f - 1f / ratio)
                1f / dbToLinear(reducedDb)          // invert to get gain reduction
            } else {
                1f
            }

            // Smooth gain changes to avoid zipper noise
            gain = if (targetGain < gain)
                attackCoef  * gain + (1f - attackCoef)  * targetGain
            else
                releaseCoef * gain + (1f - releaseCoef) * targetGain

            // Apply gain + make-up, clamp to 16-bit range
            val out = (sample * gain * makeupGain).coerceIn(-32768f, 32767f).toInt().toShort()
            buffer[i * 2]     = (out.toInt() and 0xFF).toByte()
            buffer[i * 2 + 1] = ((out.toInt() shr 8) and 0xFF).toByte()
        }
        return buffer
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun timeCoef(ms: Float, sr: Int): Float =
        // RC filter: coef = e^(-1 / (ms * sr / 1000))
        Math.E.pow(-1.0 / (ms * sr / 1000.0)).toFloat()

    private fun dbToLinear(db: Float): Float =
        10f.pow(db / 20f)

    private fun linearToDb(linear: Float): Float =
        if (linear < 1e-7f) -140f else 20f * Math.log10(linear.toDouble()).toFloat()
}
