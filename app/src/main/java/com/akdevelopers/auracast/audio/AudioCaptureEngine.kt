package com.akdevelopers.auracast.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

class AudioCaptureEngine(
    private val config: AudioQualityConfig = AudioQualityConfig.HIGH_QUALITY,
    private val onFrameReady: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var opusEncoder: OpusEncoderWrapper? = null

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private var captureJob: Job? = null

    @Volatile private var stopped = false

    private fun sourceCandidates(): List<Int> {
        val needsEffects = config.enableAec || config.enableNs || config.enableAgc
        return if (needsEffects) {
            listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )
        } else {
            listOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )
        }
    }

    @AnyThread
    @SuppressLint("MissingPermission")
    fun start() {
        stopped = false
        if (captureJob?.isActive == true) {
            Log.w("AC_Capture", "start: already running")
            return
        }
        Log.i(
            "AC_Capture",
            "start: sampleRate=${config.sampleRate} frameBytes=${config.frameBytes} opusBitrate=${config.opusBitrate}"
        )

        val minBuf = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) {
            Log.e("AC_Capture", "start: getMinBufferSize failed ($minBuf)")
            onError("AudioRecord not supported on this device")
            return
        }
        val bufSize = maxOf(minBuf, config.frameBytes * 8)

        var record: AudioRecord? = null
        for (source in sourceCandidates()) {
            val candidate = AudioRecord(
                source,
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                Log.i("AC_Capture", "start: AudioRecord OK  source=$source  bufSize=$bufSize")
                record = candidate
                break
            } else {
                Log.w("AC_Capture", "start: source=$source failed (state=${candidate.state}), trying next")
                candidate.release()
            }
        }
        if (record == null) {
            Log.e("AC_Capture", "start: ALL audio sources failed")
            onError("AudioRecord init failed on all sources")
            return
        }
        Log.i("AC_Capture", "start: AudioRecord initialized ✓ sessionId=${record.audioSessionId}")

        attachAcousticEffects(record.audioSessionId)
        audioRecord = record

        if (config.opusBitrate > 0) {
            opusEncoder = OpusEncoderWrapper(
                sampleRate = config.sampleRate,
                bitrate = config.opusBitrate,
                complexity = config.opusComplexity
            ).also { it.updateFrameSize(config.frameSamples) }
        }

        record.startRecording()
        Log.i("AC_Capture", "start: startRecording() called ✓")
        captureJob = scope.launch { captureLoop(record) }
    }

    @WorkerThread
    private suspend fun captureLoop(record: AudioRecord) {
        val buffer = ByteArray(config.frameBytes)
        var retries = 0

        while (coroutineContext.isActive) {
            val activeRecord = audioRecord ?: break
            val read = activeRecord.read(buffer, 0, config.frameBytes, AudioRecord.READ_BLOCKING)
            when {
                read == config.frameBytes -> {
                    retries = 0
                    if (config.silenceGateRms <= 0.0 || rmsOf(buffer) >= config.silenceGateRms) {
                        val frame = opusEncoder?.encode(buffer) ?: buffer.copyOf()
                        onFrameReady(frame)
                    }
                }
                read == AudioRecord.ERROR_DEAD_OBJECT -> {
                    Log.e("AC_Capture", "captureLoop: ERROR_DEAD_OBJECT retries=$retries")
                    if (!coroutineContext.isActive) {
                        break
                    }
                    if (retries++ < 5) {
                        delay(800L * retries)
                        restartRecord(activeRecord)
                    } else {
                        onError("AudioRecord dead after $retries retries")
                        break
                    }
                }
                read < 0 -> {
                    if (coroutineContext.isActive) {
                        Log.e("AC_Capture", "captureLoop: read error code=$read")
                        onError("AudioRecord read error: $read")
                    } else {
                        Log.d("AC_Capture", "captureLoop: read=$read after intentional stop — exiting cleanly")
                    }
                    break
                }
            }
        }
        Log.d("AC_Capture", "captureLoop: exited (isActive=${coroutineContext.isActive})")
    }

    @SuppressLint("MissingPermission")
    private fun restartRecord(old: AudioRecord) {
        old.stop()
        old.release()
        audioRecord = null
        val minBuf = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, config.frameBytes * 8)
        var record: AudioRecord? = null
        for (source in sourceCandidates()) {
            val candidate = AudioRecord(
                source,
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                record = candidate
                break
            } else {
                candidate.release()
            }
        }
        if (record == null) {
            return
        }
        attachAcousticEffects(record.audioSessionId)
        audioRecord = record
        record.startRecording()
    }

    private fun attachAcousticEffects(sessionId: Int) {
        if (config.enableAec && AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
        }
        if (config.enableNs && NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
        }
        if (config.enableAgc && AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(sessionId)?.also { it.enabled = true }
        }
    }

    private fun rmsOf(buf: ByteArray): Double {
        var sum = 0.0
        val shorts = buf.size / 2
        for (i in 0 until shorts) {
            val sample = (buf[i * 2].toInt() or (buf[i * 2 + 1].toInt() shl 8)).toShort()
            sum += sample * sample
        }
        return sqrt(sum / shorts)
    }

    /**
     * Hot-swap the Opus encoding bitrate without restarting the mic or the
     * AudioRecord session. The change takes effect on the very next encoded frame.
     * No-op if the encoder has not been created yet.
     */
    fun setBitrate(bps: Int) {
        opusEncoder?.setBitrate(bps)
    }

    fun stop() {
        stopped = true
        captureJob?.cancel()
        captureJob = null
        aec?.release()
        aec = null
        ns?.release()
        ns = null
        agc?.release()
        agc = null
        opusEncoder?.release()
        opusEncoder = null
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
    }

    fun release() {
        stop()
        supervisorJob.cancel()
    }
}
