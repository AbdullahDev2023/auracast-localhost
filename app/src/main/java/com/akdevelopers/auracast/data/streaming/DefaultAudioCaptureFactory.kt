package com.akdevelopers.auracast.data.streaming

import com.akdevelopers.auracast.audio.AudioCaptureEngine
import com.akdevelopers.auracast.audio.AudioQualityConfig
import com.akdevelopers.auracast.domain.streaming.AudioCaptureController
import com.akdevelopers.auracast.domain.streaming.AudioCaptureFactory

class DefaultAudioCaptureFactory(
    private val config: AudioQualityConfig = AudioQualityConfig.HIGH_QUALITY
) : AudioCaptureFactory {
    override val qualityName: String
        get() = config.preset.name

    override fun sampleRate(): Int = config.sampleRate

    override fun frameMs(): Int = config.frameMs

    override fun create(
        onFrameReady: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ): AudioCaptureController {
        val engine = AudioCaptureEngine(
            config = config,
            onFrameReady = onFrameReady,
            onError = onError
        )
        return object : AudioCaptureController {
            override fun start() = engine.start()
            override fun stop() = engine.stop()
            override fun release() = engine.release()
            override fun setBitrate(bps: Int) = engine.setBitrate(bps)
        }
    }
}
