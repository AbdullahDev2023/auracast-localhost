package com.akdevelopers.auracast.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioAttributes
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Captures the incoming caller's audio (earpiece / speaker output) during a phone
 * call using Android's AudioPlaybackCapture API (requires API 29 / Android 10+).
 *
 * Requires an active [MediaProjection] token granted by the user via the system
 * "Allow AuraCast to capture phone audio?" prompt.
 *
 * OEM caveat: Some manufacturers (Samsung One UI, Xiaomi MIUI) strip the capturable
 * USAGE_VOICE_COMMUNICATION tag at the kernel level. On those devices this silently
 * captures silence. [onSilenceDetected] fires after [SILENCE_DETECT_FRAMES] consecutive
 * silent frames so callers can fall back to mic-only mode gracefully.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class IncomingCallAudioCapture(
    private val mediaProjection: MediaProjection,
    private val sampleRate: Int,
    private val frameBytes: Int,
    private val onFrameReady: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    val onSilenceDetected: (() -> Unit)? = null,
) {
    companion object {
        private const val TAG = "AC_EarpieceCapture"
        // ~2 s worth of 20 ms frames — enough to confirm OEM suppression
        private const val SILENCE_DETECT_FRAMES = 100
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var silentFrameCount = 0

    @SuppressLint("MissingPermission")
    fun start() {
        if (captureJob?.isActive == true) {
            Log.w(TAG, "start: already running")
            return
        }
        Log.i(TAG, "start: sampleRate=$sampleRate frameBytes=$frameBytes")

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, frameBytes * 8)

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufSize)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed (state=${record.state})")
            record.release()
            onError("Earpiece AudioRecord init failed — OEM may block playback capture")
            return
        }

        audioRecord = record
        silentFrameCount = 0
        record.startRecording()
        Log.i(TAG, "startRecording() ✓ sessionId=${record.audioSessionId}")
        captureJob = scope.launch { captureLoop(record) }
    }

    private suspend fun captureLoop(record: AudioRecord) {
        val buffer = ByteArray(frameBytes)
        while (coroutineContext.isActive) {
            val read = record.read(buffer, 0, frameBytes, AudioRecord.READ_BLOCKING)
            when {
                read == frameBytes -> {
                    val rms = rmsOf(buffer)
                    if (rms < 1.0) {
                        silentFrameCount++
                        if (silentFrameCount == SILENCE_DETECT_FRAMES) {
                            Log.w(TAG, "$SILENCE_DETECT_FRAMES consecutive silent frames — OEM may block earpiece capture")
                            onSilenceDetected?.invoke()
                        }
                    } else {
                        silentFrameCount = 0
                    }
                    onFrameReady(buffer.copyOf())
                }
                read == AudioRecord.ERROR_DEAD_OBJECT -> {
                    Log.e(TAG, "ERROR_DEAD_OBJECT")
                    if (coroutineContext.isActive) onError("Earpiece AudioRecord dead object")
                    break
                }
                read < 0 -> {
                    if (coroutineContext.isActive) onError("Earpiece read error: $read")
                    break
                }
            }
        }
        Log.d(TAG, "captureLoop: exited")
    }

    fun stop() {
        Log.i(TAG, "stop")
        captureJob?.cancel()
        captureJob = null
        audioRecord?.apply { stop(); release() }
        audioRecord = null
    }

    private fun rmsOf(buf: ByteArray): Double {
        var sum = 0.0
        val shorts = buf.size / 2
        for (i in 0 until shorts) {
            val lo = buf[i * 2].toInt() and 0xFF
            val hi = buf[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble()
            sum += sample * sample
        }
        return if (shorts == 0) 0.0 else sqrt(sum / shorts)
    }
}
