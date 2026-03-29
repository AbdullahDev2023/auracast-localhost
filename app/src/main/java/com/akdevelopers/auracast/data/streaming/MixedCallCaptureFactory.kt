package com.akdevelopers.auracast.data.streaming

import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.akdevelopers.auracast.audio.AudioCallMixer
import com.akdevelopers.auracast.audio.AudioCaptureEngine
import com.akdevelopers.auracast.audio.AudioQualityConfig
import com.akdevelopers.auracast.audio.IncomingCallAudioCapture
import com.akdevelopers.auracast.domain.streaming.AudioCaptureController
import com.akdevelopers.auracast.domain.streaming.AudioCaptureFactory

/**
 * [AudioCaptureFactory] for dual-source call mode (mic + earpiece).
 *
 * Requires Android 10+ (API 29) and an active [MediaProjection] token.
 *
 * When [create] is called:
 *  1. [AudioCaptureEngine] captures the outgoing mic via VOICE_COMMUNICATION source.
 *  2. [IncomingCallAudioCapture] captures the earpiece via AudioPlaybackCapture.
 *  3. Both PCM streams feed [AudioCallMixer], whose output is delivered via [onFrameReady].
 *
 * On earpiece capture errors (e.g. OEM blocking) a warning is logged but streaming
 * continues — the mixer will simply relay mic-only frames from that point.
 *
 * Calling [AudioCaptureController.stop] / [release] on the returned controller
 * stops both capture sessions and flushes the mixer queue cleanly.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MixedCallCaptureFactory(
    private val mediaProjection: MediaProjection,
    private val config: AudioQualityConfig,
) : AudioCaptureFactory {

    companion object {
        private const val TAG = "MixedCallFactory"
    }

    override val qualityName: String get() = "call_mixed"

    override fun sampleRate(): Int = config.sampleRate
    override fun frameMs(): Int    = config.frameMs

    override fun create(
        onFrameReady: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ): AudioCaptureController {
        val frameBytes = config.sampleRate * config.frameMs / 1000 * 2  // 16-bit mono

        val mixer = AudioCallMixer(
            frameBytes    = frameBytes,
            onMixedFrame  = onFrameReady,
        )

        // Outgoing mic — uses VOICE_COMMUNICATION source (AEC + NS enabled via config)
        val micEngine = AudioCaptureEngine(
            config       = config,
            onFrameReady = { frame -> mixer.pushMicFrame(frame) },
            onError      = onError,
        )

        // Incoming earpiece — AudioPlaybackCapture via MediaProjection
        val earpieceCapture = IncomingCallAudioCapture(
            mediaProjection = mediaProjection,
            sampleRate      = config.sampleRate,
            frameBytes      = frameBytes,
            onFrameReady    = { frame -> mixer.pushEarpieceFrame(frame) },
            onError         = { msg ->
                // Non-fatal: mic frames still flow solo; log and continue.
                Log.w(TAG, "Earpiece capture error: $msg — falling back to mic-only mix")
            },
        )

        return object : AudioCaptureController {
            override fun start() {
                Log.i(TAG, "start(): launching mic + earpiece capture")
                micEngine.start()
                earpieceCapture.start()
            }

            override fun stop() {
                Log.i(TAG, "stop()")
                earpieceCapture.stop()
                micEngine.stop()
                mixer.flush()
            }

            override fun release() {
                stop()
                micEngine.release()
            }

            /** Hot-swap encoder bitrate on the mic engine; earpiece is PCM-pass-through. */
            override fun setBitrate(bps: Int) = micEngine.setBitrate(bps)
        }
    }
}
